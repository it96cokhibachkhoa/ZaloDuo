package app.zaloduo.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.content.pm.ActivityInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Màn hình duy nhất: gắn WebView Zalo (do WebViewHolder giữ) vào giao diện. */
public class MainActivity extends Activity {
    static final String EXTRA_CLICK_ID = "zw_click_id";
    static final String EXTRA_CALL = "zw_call";
    static final String ACTION_RELOAD = "app.zaloduo.client.RELOAD";

    private static final int RC_NOTIF = 1;
    private static final int RC_MEDIA = 2;
    private static final int RC_FILE = 3;
    private static final int RC_STORAGE = 4;

    /** App đang hiện trên màn hình hay không (đọc từ nhiều luồng). */
    static volatile boolean visible;

    /** Màu nền trang ở mép trên/dưới, do shim.js báo (mục 9): tô thanh trạng thái và thanh điều hướng. */
    private static int barTop = Color.WHITE;
    private static int barBottom = Color.WHITE;
    private static boolean barsLoaded;

    private FrameLayout root;
    private View statusScrim;
    private View snapshotView;
    private FrameLayout webContainer;
    private NativeChatList nativeList;
    private NativeDock nativeDock;
    /** Cỡ chữ lúc dựng màn hình (danh sách / dock native đo theo nó). */
    private int builtZoom;
    /** Chế độ BlackBerry Q20 lúc dựng màn hình (đổi trong Cài đặt thì dựng lại). */
    private boolean builtQ20;
    private FrameLayout popupContainer;
    private FrameLayout fullscreenContainer;

    private PermissionRequest pendingWebPermission;
    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean notifRequestInFlight;
    private boolean callMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CrashLog.hasPending(this)) {
            // Lần trước app bị văng: hiện log lỗi trước, chưa khởi động Zalo.
            startActivity(new Intent(this, CrashActivity.class));
            finish();
            return;
        }

        builtQ20 = Prefs.q20(this);
        // Màn vuông + bàn phím cứng: giữ hướng tự nhiên của máy, không xoay theo cảm biến (bàn phím luôn ở dưới).
        setRequestedOrientation(builtQ20 ? ActivityInfo.SCREEN_ORIENTATION_NOSENSOR : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        webContainer = new FrameLayout(this);
        popupContainer = new FrameLayout(this);
        popupContainer.setBackgroundColor(Color.WHITE);
        popupContainer.setVisibility(View.GONE);
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(View.GONE);
        statusScrim = new View(this);
        root.addView(webContainer, match());
        root.addView(statusScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
        root.addView(popupContainer, match());
        root.addView(fullscreenContainer, match());
        setContentView(root);
        // Phải gọi sau setContentView: Android 16+ văng app nếu hỏi InsetsController khi DecorView chưa được tạo.
        setupWindow();
        applyInsets();
        if (!barsLoaded) {
            barTop = Prefs.barTop(this);
            barBottom = Prefs.barBottom(this);
            barsLoaded = true;
        }
        applyBarColors();

        builtZoom = Prefs.textZoom(this);
        if (Prefs.mobileUi(this) && Prefs.nativeList(this)) {
            nativeList = new NativeChatList(this);
            nativeDock = new NativeDock(this);
            nativeDock.setGlass(nativeList.dockGlass()); // kính mờ đi cùng dock (ẩn / hiện, trượt một lớp)
            nativeList.onOpen = nativeDock::hideForOpen;
        }
        WebViewHolder.attach(this);
        attachMain(WebViewHolder.get(this));
        if (!WebViewHolder.pageReady && Prefs.mobileUi(this)) showSnapshot();
        for (ZaloWebView p : WebViewHolder.shownPopups()) showPopup(p);

        handleIntent(getIntent());
        if (!askNotificationPermissionOnFirstRun()) maybeAskBattery();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Đổi giao diện Native / WebView trong Cài đặt: dựng lại màn chính cho đúng (danh sách native có hay không).
        if ((Prefs.mobileUi(this) && Prefs.nativeList(this)) != (nativeList != null) || Prefs.q20(this) != builtQ20) recreate();
        else if (nativeList != null && Prefs.textZoom(this) != builtZoom) recreate(); // đổi cỡ chữ trong Cài đặt: native vẽ lại theo cỡ mới
        // "Theo cỡ chữ của máy": đổi cỡ chữ trong Android thì màn hình được dựng lại (native theo cỡ mới), trang Zalo
        // (WebView giữ lại) phải áp lại cỡ chữ.
        if (Prefs.textZoomSetting(this) == 0) WebViewHolder.applySettings(false);
        if (nativeDock != null && nativeDock.getParent() != null) { // khoảng cách đáy, độ đậm đổi trong Cài đặt: áp ngay
            nativeDock.setLayoutParams(nativeDock.params());
            nativeDock.applyOpacity();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        DiagLog.sys("app opened (zalo page " + (WebViewHolder.pageReady ? "ready" : "still loading") + ")");
        if (Prefs.keepAlive(this)) KeepAliveService.start(this);
        WebViewHolder.setHidden(false);
        WebViewHolder.wake("app opened");
        Notifier.cancelCallAlert(this);
    }

    @Override
    protected void onPause() {
        saveSnapshot();
        super.onPause();
    }

    @Override
    protected void onStop() {
        visible = false;
        DiagLog.add("app to background");
        DiagLog.conn("app to background | " + DiagLog.powerState(this));
        WebViewHolder.setHidden(true);
        WebViewHolder.flushCookies();
        if (callMode && !KeepAliveService.isMediaActive()) setCallMode(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }
        if (customView != null) hideCustomView();
        WebViewHolder.detach(this);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        ZaloWebView popup = WebViewHolder.topPopup();
        if (popup != null) {
            if (popup.canGoBack()) popup.goBack();
            else if (KeepAliveService.isMediaActive()) moveTaskToBack(true); // không lỡ tay tắt cuộc gọi
            else WebViewHolder.closePopup(popup);
            return;
        }
        WebView main = WebViewHolder.peek();
        if (main != null && WebViewHolder.pageReady) {
            // Để trang đóng lớp trên cùng trước (xem ảnh, menu, hộp thoại, khung chat); không có gì thì mới về nền.
            main.evaluateJavascript("!!(window.__zw&&window.__zw.back&&window.__zw.back())", r -> {
                if (!"true".equals(r)) fallbackBack();
            });
            return;
        }
        fallbackBack();
    }

    /**
     * Bàn phím cứng / trackpad (BlackBerry Q20): ở danh sách chat native thì danh sách bắt phím trước (chọn dòng, mở,
     * gõ để tìm — NativeChatList.onKey). Phím Menu (phím BlackBerry) mở Cài đặt của app. Còn lại đi tới trang như cũ.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (nativeList != null && customView == null && WebViewHolder.topPopup() == null
                && !WebViewHolder.mobileChatOpen && nativeList.onKey(e)) {
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && builtQ20) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        // Mở chat bằng phím (không chạm) thì WebView có thể chưa giữ focus của Android: phím gõ không tới được trang.
        if (builtQ20 && WebViewHolder.mobileChatOpen && customView == null && WebViewHolder.topPopup() == null
                && e.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            WebView wv = WebViewHolder.peek();
            if (wv != null && !wv.hasFocus()) wv.requestFocus();
        }
        return super.dispatchKeyEvent(e);
    }

    /** Ô nhập trong trang được focus / bỏ focus (JsBridge.editing). */
    void onEditing(boolean on) {
        if (nativeList != null) nativeList.onEditing(on);
    }

    private void fallbackBack() {
        if (WebViewHolder.mobileChatOpen) {
            // Đang xem một cuộc trò chuyện: quay về danh sách chat.
            WebViewHolder.mobileChatOpen = false;
            WebViewHolder.evalMain("window.__zw&&window.__zw.mobileHome&&window.__zw.mobileHome()");
            return;
        }
        WebView wv = WebViewHolder.peek();
        if (wv != null && wv.canGoBack() && !WebViewHolder.isHome(wv.getUrl())) {
            wv.goBack();
            return;
        }
        // Không đóng app: chỉ đưa về nền để vẫn nhận tin nhắn.
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ intent

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        if (ACTION_RELOAD.equals(intent.getAction())) WebViewHolder.reload();
        String clickId = intent.getStringExtra(EXTRA_CLICK_ID);
        if (clickId != null) {
            intent.removeExtra(EXTRA_CLICK_ID);
            // Kích hoạt notification.onclick của Zalo Web -> mở đúng cuộc trò chuyện.
            WebViewHolder.evalMain("window.__zw&&window.__zw.click(" + JSONObject.quote(clickId) + ")");
        }
        if (intent.getBooleanExtra(EXTRA_CALL, false)) {
            intent.removeExtra(EXTRA_CALL);
            Notifier.cancelCallAlert(this);
            setCallMode(true);
        }
    }

    // ------------------------------------------------------------------ WebView & popup

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void attachMain(WebView wv) {
        WebViewHolder.unpark();
        WebViewHolder.detachFromParent(wv);
        webContainer.removeAllViews();
        // Danh sách chat native nằm dưới WebView (xem NativeChatList): trang trong suốt ở vùng danh sách.
        if (nativeList != null) webContainer.addView(nativeList, match());
        webContainer.addView(wv, match());
        if (nativeDock != null) webContainer.addView(nativeDock, nativeDock.params()); // dock native nằm trên trang
        // Trang có thể đã tải xong từ trước (dịch vụ chạy nền mở trang khi chưa có màn hình) và chỉ gửi khi dữ liệu đổi:
        // xin gửi lại danh sách + trạng thái dock cho danh sách / dock native vừa dựng (không thì kẹt ở danh sách web).
        if (nativeList != null) {
            WebViewHolder.evalMain("window.__zw&&window.__zw.requestConvList&&window.__zw.requestConvList();"
                    + "window.__zw&&window.__zw.nativeDock&&window.__zw.nativeDock(true)");
        }
    }

    /** Danh sách hội thoại mới từ trang (JsBridge.convList). */
    void onConvList(String json) {
        if (nativeList != null) nativeList.setData(json);
        else NativeChatList.save(this, json);
    }

    /** Trạng thái kết nối máy chủ Zalo (JsBridge.connState): on / wait / off. */
    void onConnState(String state) {
        if (nativeList != null) nativeList.setConn(state);
    }

    /** Trạng thái dock từ trang (JsBridge.dockState). */
    void onDockState(String json) {
        if (nativeDock != null) nativeDock.setState(json);
    }

    /** Bật/tắt + vị trí danh sách chat native (JsBridge.nativeLayout). */
    void onNativeLayout(String json, ZaloWebView wv) {
        if (nativeList != null) nativeList.setLayout(json, wv);
    }

    /** Màu nền mép dưới của trang (WebView trở lại màu này khi tắt danh sách native). */
    static int pageBottomColor() {
        return barBottom;
    }

    void onMainWebViewReplaced(WebView wv) {
        attachMain(wv);
        onPopupsChanged();
    }

    void showPopup(ZaloWebView p) {
        WebViewHolder.detachFromParent(p);
        popupContainer.addView(p, match());
        onPopupsChanged();
    }

    void onPopupsChanged() {
        popupContainer.setVisibility(popupContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ ảnh chụp danh sách chat khi mở app

    /*
     * Khi trang Zalo phải tải lại từ đầu (cài đè app, app bị tắt hẳn), Zalo hiện "Đang đăng nhập…" vài giây.
     * Thay vào đó hiện ảnh chụp danh sách chat lần trước, tới khi shim.js báo danh sách thật đã hiện (pageReady).
     * Ảnh nằm trong bộ nhớ đệm riêng của app.
     */
    private File snapshotFile() {
        return new File(getCacheDir(), "last_screen.jpg");
    }

    /** Trang đang ở màn đăng nhập (mã QR, id.zalo.me...): bỏ lớp che, hiện trang cho người dùng đăng nhập. */
    void onLoginPage() {
        if (nativeList != null) nativeList.cancelBoot();
        if (nativeDock != null) nativeDock.hideNow();
        hideSnapshot();
    }

    /** Trang Zalo bắt đầu tải lại trong khi app đang mở. */
    void onPageReload() {
        WebViewHolder.editing = false; // trang mới chưa có ô nhập nào được focus
        if (nativeList != null) nativeList.onPageReload();
        if (snapshotView == null && Prefs.mobileUi(this)) showSnapshot();
    }

    private void showSnapshot() {
        // Có danh sách chat native: hiện luôn danh sách đã lưu (dữ liệu thật, vẽ bằng app) thay cho ảnh chụp, không có
        // vòng xoay (trạng thái kết nối hiện ở nhãn trên thanh trên khi trang xong). snapshotView chỉ còn là mốc "đang
        // tải" cho hideSnapshot / setSystemBarColors.
        if (nativeList != null && Prefs.nativeList(this) && nativeList.boot(true)) {
            DiagLog.add("native list shown while zalo loads");
            FrameLayout frame = new FrameLayout(this);
            snapshotView = frame;
            webContainer.addView(snapshotView, match());
            // Không có mốc hết giờ: giao diện native giữ màn hình tới khi trang sẵn sàng (pageReady), chỉ nhường trang
            // khi trang cần người dùng (màn đăng nhập, onLoginPage). Lỗi giao diện native: tắt trong Cài đặt.
            return;
        }
        File f = snapshotFile();
        Bitmap bmp = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        DiagLog.add(bmp != null ? "snapshot shown while zalo loads" : "no snapshot saved, zalo loading screen visible");
        if (bmp == null) return;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(barBottom);
        frame.setClickable(true); // ảnh tĩnh: không cho chạm xuyên xuống trang đang tải
        ImageView img = new ImageView(this);
        img.setImageBitmap(bmp);
        img.setScaleType(ImageView.ScaleType.FIT_START);
        frame.addView(img, match());
        frame.addView(loadingSpinner(), spinnerParams());
        snapshotView = frame;
        webContainer.addView(snapshotView, match());
        // Không lên được danh sách (ví dụ phải quét lại mã QR): bỏ ảnh sau một lúc.
        snapshotView.postDelayed(this::hideSnapshot, 20_000);
    }

    /** Vòng xoay giữa màn hình trên ảnh chụp: báo là trang thật đang tải, ảnh chưa chạm được. */
    private View loadingSpinner() {
        boolean dark = Color.luminance(barBottom) < 0.5f;
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(0xFF0068FF));
        int pad = dp(10);
        p.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(dark ? 0xFF2B2F36 : Color.WHITE);
        p.setBackground(bg);
        p.setElevation(dp(6));
        return p;
    }

    private FrameLayout.LayoutParams spinnerParams() {
        int size = dp(52);
        return new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void hideSnapshot() {
        final View v = snapshotView;
        if (v == null) return;
        snapshotView = null;
        if (nativeList != null) nativeList.boot(false);
        // Màu thanh hệ thống gửi trong lúc ảnh che trang đã bị bỏ qua (setSystemBarColors): xin lại màu thật.
        WebViewHolder.evalMain("window.__zw&&window.__zw.refreshBars&&window.__zw.refreshBars()");
        v.animate().alpha(0f).setDuration(180).withEndAction(() -> webContainer.removeView(v)).start();
    }

    /** Chụp vùng WebView khi rời app, chỉ khi đang ở danh sách chat và trang đã tải xong. */
    private void saveSnapshot() {
        WebView wv = WebViewHolder.peek();
        if (wv == null || !WebViewHolder.pageReady || WebViewHolder.mobileChatOpen || snapshotView != null
                || !Prefs.mobileUi(this) || wv.getWidth() == 0 || wv.getHeight() == 0
                || (nativeList != null && nativeList.hasData())) { // mở app dùng danh sách native, không cần ảnh
            return;
        }
        int[] loc = new int[2];
        wv.getLocationInWindow(loc);
        final Bitmap bmp = Bitmap.createBitmap(wv.getWidth(), wv.getHeight(), Bitmap.Config.ARGB_8888);
        final File f = snapshotFile();
        try {
            PixelCopy.request(getWindow(), new Rect(loc[0], loc[1], loc[0] + wv.getWidth(), loc[1] + wv.getHeight()),
                    bmp, result -> {
                        if (result != PixelCopy.SUCCESS) {
                            bmp.recycle();
                            return;
                        }
                        App.IO.execute(() -> {
                            try (FileOutputStream out = new FileOutputStream(f)) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                            } catch (IOException e) {
                                Log.w(WebViewHolder.TAG, "save snapshot failed", e);
                            } finally {
                                bmp.recycle();
                            }
                        });
                    }, App.MAIN);
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "snapshot failed", e);
            bmp.recycle();
        }
    }

    // ------------------------------------------------------------------ cửa sổ, tràn viền, toàn màn hình

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.getDecorView(); // bảo đảm DecorView đã tồn tại trước khi đụng tới thanh hệ thống
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            if (Build.VERSION.SDK_INT < 35) { // Android 15+ luôn tràn viền, hai lệnh này không còn tác dụng
                w.setStatusBarColor(Color.TRANSPARENT);
                w.setNavigationBarColor(Color.TRANSPARENT);
            }
            WindowInsetsController c = insetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    /** Android 11+: tự chừa chỗ cho thanh trạng thái, thanh điều hướng và bàn phím. */
    private void applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
            webContainer.setPadding(i.left, i.top, i.right, i.bottom);
            popupContainer.setPadding(i.left, i.top, i.right, i.bottom);
            ViewGroup.LayoutParams lp = statusScrim.getLayoutParams();
            if (lp.height != i.top) {
                lp.height = i.top;
                statusScrim.setLayoutParams(lp);
            }
            return insets;
        });
    }

    static void setSystemBarColors(Context c, int top, int bottom) {
        if (barsLoaded && top == barTop && bottom == barBottom) return;
        // Ảnh chụp đang che trang đang tải ("Đang đăng nhập…" nền trắng): giữ màu của ảnh chụp, không lưu màu tạm.
        MainActivity host = WebViewHolder.host();
        if (host != null && host.snapshotView != null) return;
        barTop = top;
        barBottom = bottom;
        barsLoaded = true;
        Prefs.put(c, Prefs.BAR_TOP, top);
        Prefs.put(c, Prefs.BAR_BOTTOM, bottom);
        WebView wv = WebViewHolder.peek();
        // Danh sách chat native đang hiện: WebView phải trong suốt, tô nền đặc là che mất danh sách bên dưới.
        boolean nativeOn = host != null && host.nativeList != null && host.nativeList.getVisibility() == View.VISIBLE;
        if (wv != null && !nativeOn) wv.setBackgroundColor(bottom);
        if (host != null) host.applyBarColors();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Hệ thống có thể đặt lại màu icon thanh trạng thái theo theme khi cửa sổ hiện lên.
        if (hasFocus) applyBarColors();
    }

    /** Thanh trạng thái theo màu mép trên của trang, thanh điều hướng theo mép dưới; chữ/icon sáng hay tối tùy nền. */
    @SuppressWarnings("deprecation")
    private void applyBarColors() {
        statusScrim.setBackgroundColor(barTop);
        root.setBackgroundColor(barBottom);
        boolean lightTop = Color.luminance(barTop) > 0.5f;
        boolean lightBottom = Color.luminance(barBottom) > 0.5f;
        if (Build.VERSION.SDK_INT >= 33) {
            // Hệ thống nhớ theme này cho các lần mở app sau: màn hình chờ tối khi Zalo để giao diện tối.
            try {
                getSplashScreen().setSplashScreenTheme(lightBottom ? R.style.AppTheme : R.style.AppTheme_Dark);
            } catch (RuntimeException ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = insetsController();
            if (c == null) return;
            c.setSystemBarsAppearance(
                    (lightTop ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                            | (lightBottom ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0),
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    (lightTop ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0)
                            | (lightBottom ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        }
    }

    /** InsetsController lấy từ DecorView (không bao giờ null khi đã setContentView). */
    private WindowInsetsController insetsController() {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            View decor = getWindow().getDecorView();
            WindowInsetsController c = decor.getWindowInsetsController();
            return c != null ? c : getWindow().getInsetsController();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = insetsController();
            if (c == null) return;
            if (hidden) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View d = getWindow().getDecorView();
            if (hidden) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                applyBarColors();
            }
        }
    }

    void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        fullscreenContainer.addView(view, match());
        fullscreenContainer.setVisibility(View.VISIBLE);
        setSystemBarsHidden(true);
    }

    void hideCustomView() {
        if (customView == null) return;
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        customView = null;
        setSystemBarsHidden(false);
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customViewCallback = null;
        if (cb != null) cb.onCustomViewHidden();
    }

    // ------------------------------------------------------------------ cuộc gọi

    @SuppressWarnings("deprecation")
    private void setCallMode(boolean on) {
        callMode = on;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } else {
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (on) getWindow().addFlags(flags);
            else getWindow().clearFlags(flags);
        }
    }

    void onMediaActiveChanged(boolean active) {
        if (active) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (callMode) setCallMode(false);
        }
    }

    // ------------------------------------------------------------------ quyền

    /** @return true nếu đã hiện hộp thoại xin quyền thông báo. */
    private boolean askNotificationPermissionOnFirstRun() {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false;
        if (Prefs.bool(this, Prefs.NOTIF_ASKED, false)) return false;
        requestNotificationPermission();
        return true;
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notifRequestInFlight) return;
        notifRequestInFlight = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIF);
    }

    void requestWebPermissions(PermissionRequest req, List<String> perms) {
        if (pendingWebPermission != null) pendingWebPermission.deny();
        pendingWebPermission = req;
        requestPermissions(perms.toArray(new String[0]), RC_MEDIA);
    }

    void onWebPermissionCanceled(PermissionRequest req) {
        if (pendingWebPermission == req) pendingWebPermission = null;
    }

    void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_NOTIF) {
            notifRequestInFlight = false;
            Prefs.put(this, Prefs.NOTIF_ASKED, true);
            JsBridge.flushPermissionCallbacks(this);
            maybeAskBattery();
        } else if (requestCode == RC_MEDIA) {
            PermissionRequest req = pendingWebPermission;
            pendingWebPermission = null;
            if (req != null) WebViewHolder.grantAvailable(req);
        }
    }

    private void maybeAskBattery() {
        if (!Prefs.keepAlive(this) || Prefs.bool(this, Prefs.BATTERY_ASKED, false)) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        Prefs.put(this, Prefs.BATTERY_ASKED, true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_ok, (d, which) -> SettingsActivity.requestIgnoreBattery(this))
                .setNegativeButton(R.string.later, null)
                .show();
    }

    // ------------------------------------------------------------------ chọn tệp để gửi

    boolean showFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        Intent intent = params.createIntent();
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(Intent.createChooser(intent, null), RC_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }
}
