package app.zaloduo.client;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Cầu nối JS -> Android, lộ ra trong trang dưới tên window.ZaloBridge. Chỉ shim.js gọi tới.
 * Các hàm @JavascriptInterface chạy trên luồng riêng của WebView, không phải luồng chính.
 */
final class JsBridge {
    private final Context app;
    private final ZaloWebView owner;

    /** Các lời gọi Notification.requestPermission() đang chờ người dùng trả lời hộp thoại quyền. */
    private static final List<Object[]> pendingPermission = new ArrayList<>();

    JsBridge(Context app, ZaloWebView owner) {
        this.app = app.getApplicationContext();
        this.owner = owner;
    }

    /** Chỉ phục vụ trang Zalo (https://*.zalo.me ...). Trang khác lỡ được mở cũng không gọi được. */
    private boolean trusted() {
        return WebViewHolder.isTrustedUrl(owner.lastUrl);
    }

    @JavascriptInterface
    public void showNotification(String id, String title, String body, String tag, String icon, boolean silent) {
        if (!trusted()) return;
        Notifier.show(app, id, title, body, tag, icon, silent);
    }

    @JavascriptInterface
    public void cancelNotification(String id, String tag) {
        if (!trusted()) return;
        Notifier.cancel(app, id);
    }

    @JavascriptInterface
    public String permission() {
        return Notifier.permissionState(app);
    }

    @JavascriptInterface
    public void requestPermission(final String callbackId) {
        if (!trusted()) return;
        App.MAIN.post(() -> {
            String state = Notifier.permissionState(app);
            MainActivity a = WebViewHolder.host();
            if (!"default".equals(state) || a == null || Build.VERSION.SDK_INT < 33) {
                deliverPermission(owner, callbackId, state);
                return;
            }
            synchronized (pendingPermission) {
                pendingPermission.add(new Object[]{owner, callbackId});
            }
            a.requestNotificationPermission();
        });
    }

    /** Gọi từ MainActivity sau khi người dùng trả lời hộp thoại quyền thông báo. */
    static void flushPermissionCallbacks(Context ctx) {
        String state = Notifier.permissionState(ctx);
        List<Object[]> copy;
        synchronized (pendingPermission) {
            copy = new ArrayList<>(pendingPermission);
            pendingPermission.clear();
        }
        for (Object[] p : copy) deliverPermission((WebView) p[0], (String) p[1], state);
    }

    private static void deliverPermission(WebView wv, String callbackId, String state) {
        wv.evaluateJavascript("window.__zw&&window.__zw.permResult(" + JSONObject.quote(callbackId) + ","
                + JSONObject.quote(state) + ")", null);
    }

    @JavascriptInterface
    public boolean isHidden() {
        return !MainActivity.visible;
    }

    @JavascriptInterface
    public void mediaActive(final boolean active, final boolean video) {
        if (!trusted()) return;
        App.MAIN.post(() -> KeepAliveService.setMediaActive(app, active, video));
    }

    @JavascriptInterface
    public void ringing(boolean on) {
        if (!trusted()) return;
        if (on) Notifier.showRingAlert(app);
        else Notifier.cancelCallAlert(app);
    }

    /** Giao diện điện thoại: true = đang mở khung chat, false = đang ở danh sách chat. */
    @JavascriptInterface
    public void mobileState(boolean chatOpen) {
        if (!trusted() || owner.popup) return;
        WebViewHolder.mobileChatOpen = chatOpen;
    }

    /** Danh sách hội thoại (JSON, mục 12 của shim.js) cho danh sách chat native; app đang ở nền thì chỉ lưu lại. */
    @JavascriptInterface
    public void convList(final String json) {
        if (!trusted() || owner.popup || json == null) return;
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onConvList(json);
            else NativeChatList.save(app, json);
        });
    }

    /** Danh sách chat native bật/tắt, vị trí thanh trên + dock, màu nền (JSON, mục 12 của shim.js). */
    @JavascriptInterface
    public void nativeLayout(final String json) {
        if (!trusted() || owner.popup || json == null) return;
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onNativeLayout(json, owner);
        });
    }

    /** Trạng thái dock (JSON, mục 8 của shim.js) cho dock native (NativeDock). */
    @JavascriptInterface
    public void dockState(final String json) {
        if (!trusted() || owner.popup || json == null) return;
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onDockState(json);
        });
    }

    /** Tải trước ảnh HD / video của các tin vừa nạp (JSON mảng link, mục 15 của shim.js) xuống bộ nhớ của app. */
    @JavascriptInterface
    public void prefetch(final String json) {
        if (!trusted() || json == null) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (int i = 0; i < a.length() && i < 200; i++) urls.add(a.optString(i));
            MediaCache.prefetch(app, urls);
        } catch (org.json.JSONException ignored) {
        }
    }

    /** Trạng thái kết nối máy chủ Zalo ("on" / "wait" / "off", mục 11 của shim.js): hiện ở thông báo chạy nền. */
    @JavascriptInterface
    public void connState(final String state) {
        if (!trusted() || owner.popup || state == null) return;
        App.MAIN.post(() -> {
            KeepAliveService.setConn(state);
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onConnState(state); // nhãn dưới tên trên thanh trên native
        });
    }

    /** Trang đang ở màn đăng nhập (mã QR...): phải hiện trang cho người dùng, bỏ lớp che. */
    @JavascriptInterface
    public void needLogin() {
        if (!trusted() || owner.popup) return;
        App.MAIN.post(() -> {
            DiagLog.sys("zalo login screen");
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onLoginPage();
        });
    }

    /** Danh sách chat thật đã hiện: bỏ ảnh chụp che màn hình đăng nhập. */
    @JavascriptInterface
    public void pageReady() {
        if (!trusted() || owner.popup) return;
        App.MAIN.post(() -> {
            if (!WebViewHolder.pageReady) DiagLog.add("chat list shown");
            WebViewHolder.pageReady = true;
            MainActivity a = WebViewHolder.host();
            if (a != null) a.hideSnapshot();
        });
    }

    /** Màu nền trang ở mép trên/dưới (#rrggbb): tô thanh trạng thái và thanh điều hướng cho khớp. */
    @JavascriptInterface
    public void systemBars(String top, String bottom) {
        if (!trusted() || owner.popup) return;
        final int t, b;
        try {
            t = Color.parseColor(top);
            b = Color.parseColor(bottom);
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        App.MAIN.post(() -> MainActivity.setSystemBarColors(app, t, b));
    }

    @JavascriptInterface
    public void saveFile(String name, String mime, String base64) {
        if (!trusted()) return;
        Downloads.saveBase64(app, name, mime, base64);
    }

    /** Chia sẻ tệp (ảnh đang xem) sang app khác qua bảng chia sẻ của Android. */
    @JavascriptInterface
    public void shareFile(String name, String mime, String base64) {
        if (!trusted() || base64 == null) return;
        App.MAIN.post(() -> ShareFiles.share(app, name, mime, base64));
    }

    /** Sự kiện từ shim.js cho nhật ký chẩn đoán (không có nội dung tin nhắn). */
    @JavascriptInterface
    public void diag(String event) {
        if (!trusted() || owner.popup || event == null) return;
        DiagLog.add("web: " + (event.length() > 160 ? event.substring(0, 160) : event));
    }

    /** Sự kiện kết nối máy chủ Zalo từ shim.js (mục 11) cho nhật ký kết nối. */
    @JavascriptInterface
    public void connLog(String event) {
        if (!trusted() || owner.popup || event == null) return;
        DiagLog.conn("web: " + (event.length() > 240 ? event.substring(0, 240) : event));
    }

    @JavascriptInterface
    public void log(String message) {
        Log.d(WebViewHolder.TAG, "js: " + message);
    }
}
