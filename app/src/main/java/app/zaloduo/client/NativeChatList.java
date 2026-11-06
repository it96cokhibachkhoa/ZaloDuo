package app.zaloduo.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Danh sách chat vẽ bằng giao diện Android, nằm dưới WebView trong webContainer. Dữ liệu do shim.js (mục 12) lấy từ
 * Zalo Web và gửi qua JsBridge.convList; lưu ở bộ nhớ app (convlist.json) để mở app là có ngay. Thanh trên và dock vẫn
 * là của trang web, vẽ đè lên; vùng ở giữa trang làm trong suốt và WebView nhường chạm ở đó cho danh sách này
 * (ZaloWebView.passTop / passExclude). Chạm một dòng: mở hội thoại trong Zalo Web (__zw.openConv).
 */
final class NativeChatList extends FrameLayout {
    private static final String FILE = "convlist.json";

    private final ListView listView;
    private final Adapter adapter = new Adapter();
    private final List<Item> items = new ArrayList<>();
    private final float density;
    // Màu như dòng chat của Zalo Web (giao diện sáng); shim.js gửi màu thật theo giao diện đang dùng.
    private int bg = Color.WHITE, fg = 0xFF081B3A, fg2 = 0xFF5A6981;
    private int topPx;
    private boolean announced;
    /** Cỡ chữ theo cài đặt "Cỡ chữ" của app (như trang web), không theo cỡ chữ hệ thống. */
    private final float zoom;
    /** Chế độ BlackBerry Q20 (Prefs.q20): dòng thấp hơn, ảnh nhỏ hơn để màn vuông ~336 dp hiện được nhiều hội thoại hơn. */
    private final boolean q20;
    private final int rowAv;

    static final class Item {
        String id, name, avatar, text, icon;
        String[] members; // nhóm không có ảnh riêng: ảnh vài thành viên
        boolean group, muted, me, missCall;
        long time;
        int unread, mention, unreadDot, pin, total;
    }

    NativeChatList(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        zoom = Prefs.textZoom(c) / 100f;
        q20 = Prefs.q20(c);
        rowAv = q20 ? 40 : 48;
        listView = new ListView(c) {
            /**
             * Android 12+: nội dung danh sách ghi một lần mỗi khung hình vào nút content; kính mờ dưới dock vẽ lại chính
             * nút đó (drawRenderNode) thay vì ghi lại cả danh sách lần nữa — rẻ, cuộn mượt, kính luôn khớp khung hình.
             */
            @Override
            public void draw(Canvas cv) {
                if (android.os.Build.VERSION.SDK_INT < 31 || !cv.isHardwareAccelerated()) {
                    super.draw(cv);
                    return;
                }
                if (content == null) content = new android.graphics.RenderNode("zd-list");
                content.setPosition(0, 0, getWidth(), getHeight());
                Canvas rc = content.beginRecording();
                try {
                    super.draw(rc);
                } finally {
                    content.endRecording();
                }
                cv.drawRenderNode(content);
                if (glass != null && glass.getVisibility() == VISIBLE) glass.invalidate();
            }
        };
        listView.setDivider(null);
        listView.setSelector(new GradientDrawable()); // không có nền chọn mặc định
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(OVER_SCROLL_NEVER);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> open(pos));
        // Chạm màn hình: bỏ dòng đang chọn bằng trackpad / phím (như BlackBerry: chạm là thoát chế độ con trỏ).
        listView.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) setKeySel(-1);
            return false;
        });
        // Giữ lâu: menu thao tác hội thoại của Zalo kiểu "focus" như app Zalo: nền tối đi, dòng được giữ nổi lên thành
        // thẻ, menu xổ dưới thẻ. Menu là của trang web (vẽ trên WebView) nên thẻ cũng do trang vẽ, từ ảnh chụp dòng này
        // (giữ nguyên ảnh nhóm ghép, badge...). y, h theo px CSS của WebView (cùng gốc với view này).
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (booting || opening()) return true;
            Item it = items.get(pos);
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            int[] a = new int[2], b = new int[2];
            view.getLocationInWindow(a);
            getLocationInWindow(b);
            int yCss = Math.round((a[1] - b[1]) / density), hCss = Math.round(view.getHeight() / density);
            view.setPressed(false);
            String shot = snapshot(view);
            WebViewHolder.evalMain("window.__zw&&window.__zw.convMenu&&window.__zw.convMenu(" + JSONObject.quote(it.id) + "," + pos +
                    "," + yCss + "," + hCss + "," + JSONObject.quote(shot) + ")");
            return true;
        });
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            private int lastTop = 0, lastFirst = 0;

            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE) settleMoves(); // dòng đang trượt về chỗ: dừng ngay, kẻo lệch khi cuộn
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 2) loadMore(); // gần cuối (hay danh sách ngắn hơn màn hình)
                View v = view.getChildAt(0);
                if (v == null) return;
                // Độ dịch tính bằng px CSS (giống khi cuộn danh sách web) để shim.js ẩn/hiện dock + thanh trên.
                int top = v.getTop();
                int dy = first == lastFirst ? lastTop - top : (first > lastFirst ? v.getHeight() : -v.getHeight());
                lastTop = top;
                lastFirst = first;
                boolean atTop = first == 0 && top >= listView.getPaddingTop() - 2;
                int dyCss = Math.round(dy / density);
                glass.invalidate(); // kính dưới dock vẽ lại phần danh sách vừa cuộn qua
                if (dyCss == 0 && !atTop) return;
                WebViewHolder.evalMain("window.__zw&&window.__zw.nativeScroll&&window.__zw.nativeScroll(" + dyCss + "," + atTop + ")");
            }
        });
        addView(listView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        glass = new DockGlass(c); // nằm trong dock native (NativeDock.setGlass), không nằm trong danh sách
        glass.setVisibility(GONE);
        header = new Header(c);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));
        WebViewHolder.evalMain("window.__zw&&window.__zw.connReport&&window.__zw.connReport()"); // trạng thái hiện tại
        setVisibility(GONE);
        load();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Ảnh một dòng (nền trong suốt) dạng data URL PNG; "" nếu không chụp được. */
    private static String snapshot(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return "";
        try {
            Bitmap bm = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bm));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            bm.recycle();
            return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (RuntimeException | OutOfMemoryError e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ dữ liệu

    /** Danh sách mới từ shim.js (chuỗi JSON), lưu xuống bộ nhớ app. */
    void setData(String json) {
        boolean had = hasData();
        if (!parse(json)) return;
        // Tab lọc trống / tên chưa đủ: giữ bản lưu cũ để lần mở app sau vẫn có danh sách đầy đủ.
        if (hasData() && !borrowed) save(getContext(), json);
        // Rỗng -> có dữ liệu (đổi lại tab lọc từ một tab trống): bật lại danh sách theo bố cục cuối của trang.
        if (had != hasData() && lastLayout != null) setLayout(lastLayout, lastWv);
        if (!announced) { // báo trang: đã có danh sách native, được ẩn danh sách web
            announced = true;
            WebViewHolder.evalMain("window.__zw&&window.__zw.nativeReady&&window.__zw.nativeReady(true)");
        }
    }

    /** Lưu danh sách vào bộ nhớ riêng của app (luồng IO). */
    static void save(Context c, final String json) {
        final File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        App.IO.execute(() -> {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(WebViewHolder.TAG, "save conv list", e);
            }
        });
    }

    /** Lần mở trước: hiện ngay danh sách đã lưu (trang Zalo chưa tải xong vẫn có danh sách). */
    private void load() {
        File f = new File(getContext().getFilesDir(), FILE);
        if (!f.exists()) return;
        try {
            parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(WebViewHolder.TAG, "load conv list", e);
        }
    }

    boolean hasData() {
        return !items.isEmpty();
    }

    private boolean borrowed; // lần parse gần nhất phải dùng tạm tên cũ: không lưu bản thiếu tên này

    // ------------------------------------------------------------------ dòng trượt về chỗ mới khi danh sách đổi

    /** Mép trên của các dòng đang hiện, theo mã hội thoại (trước khi cập nhật). */
    private Map<String, Integer> captureTops() {
        Map<String, Integer> m = new HashMap<>();
        int first = listView.getFirstVisiblePosition();
        for (int i = 0; i < listView.getChildCount(); i++) {
            int pos = first + i;
            if (pos < items.size()) m.put(items.get(pos).id, listView.getChildAt(i).getTop());
        }
        return m;
    }

    /**
     * Danh sách đổi (tin mới đẩy hội thoại lên đầu, nạp xong sau khi kết nối...): thay vì nhảy phắt, mỗi dòng trượt từ
     * chỗ cũ về chỗ mới; dòng trước đó nằm ngoài màn hình trượt vào từ phía nó, dòng mới hẳn mờ dần hiện ra. Đổi tab
     * (trượt cả trang) thì thôi.
     */
    private void animateMoves(final Map<String, Integer> oldTops, final List<String> oldOrder) {
        if (oldTops.isEmpty() || slideOld != null || getVisibility() != VISIBLE || !isShown()) return;
        listView.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                listView.getViewTreeObserver().removeOnPreDrawListener(this);
                int first = listView.getFirstVisiblePosition(), h = listView.getHeight();
                android.view.animation.Interpolator ease = new android.view.animation.PathInterpolator(.2f, .9f, .25f, 1f);
                for (int i = 0; i < listView.getChildCount(); i++) {
                    int pos = first + i;
                    if (pos >= items.size()) break;
                    View row = listView.getChildAt(i);
                    String id = items.get(pos).id;
                    Integer was = oldTops.get(id);
                    float from;
                    if (was != null) from = was - row.getTop();
                    else {
                        int oldIdx = oldOrder.indexOf(id);
                        if (oldIdx < 0) { // hội thoại mới hẳn
                            row.setAlpha(0f);
                            row.animate().alpha(1f).setDuration(260).setInterpolator(ease).start();
                            continue;
                        }
                        // Trước đó ở ngoài màn hình: trượt vào từ phía trên / dưới.
                        from = oldIdx < first ? -row.getHeight() * 2 : h - row.getTop();
                    }
                    if (Math.abs(from) < 1) continue;
                    row.setTranslationY(from);
                    row.animate().translationY(0).setDuration(320).setInterpolator(ease).start();
                }
                return true;
            }
        });
    }

    private void settleMoves() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            View row = listView.getChildAt(i);
            row.animate().cancel();
            row.setTranslationY(0);
            row.setAlpha(1f);
        }
    }

    private boolean parse(String json) {
        try {
            JSONArray a = new JSONArray(json);
            List<Item> next = new ArrayList<>(a.length());
            Map<String, Item> old = new HashMap<>();
            for (Item it : items) old.put(it.id, it);
            borrowed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id");
                it.group = o.optBoolean("g");
                it.name = o.optString("n");
                it.avatar = o.optString("a");
                it.time = o.optLong("t");
                it.text = o.optString("m");
                it.unread = o.optInt("u");
                it.mention = o.optInt("at");
                it.unreadDot = o.optInt("ud");
                it.pin = o.optInt("p");
                it.muted = o.optBoolean("mu");
                it.me = o.optBoolean("me");
                it.icon = o.optString("ic");
                it.missCall = o.optBoolean("miss");
                it.total = o.optInt("tm");
                JSONArray ms = o.optJSONArray("ms");
                it.members = new String[ms == null ? 0 : ms.length()];
                for (int k = 0; k < it.members.length; k++) it.members[k] = ms.optString(k);
                // Trang vừa tải lại, danh bạ chưa nạp: tên là mã số, chưa có ảnh — dùng tạm tên / ảnh đã biết.
                Item was = old.get(it.id);
                if (was != null && it.name.equals(it.id) && !was.name.equals(was.id)) {
                    it.name = was.name;
                    if (it.avatar.isEmpty()) it.avatar = was.avatar;
                    if (it.members.length == 0) it.members = was.members;
                    if (it.total == 0) it.total = was.total;
                    borrowed = true;
                }
                next.add(it);
            }
            Map<String, Integer> oldTops = captureTops();
            List<String> oldOrder = new ArrayList<>(items.size());
            for (Item it : items) oldOrder.add(it.id);
            items.clear();
            items.addAll(next);
            adapter.notifyDataSetChanged();
            animateMoves(oldTops, oldOrder);
            if (glass != null) glass.post(glass::invalidate); // sau khi danh sách vẽ lại dòng mới
            if (slideOld != null) { // dữ liệu tab mới đã tới: về đầu danh sách rồi trượt vào
                listView.setSelection(0);
                listView.post(this::runTabSlide);
            }
            return true;
        } catch (Exception e) {
            Log.w(WebViewHolder.TAG, "bad conv list", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ bố cục

    /**
     * Trạng thái từ shim.js: on (đang ở danh sách chat), top (mép dưới thanh trên, px CSS), dock [l,t,r,b], bg (màu
     * nền danh sách). Hiện/ẩn danh sách, đặt khoảng trống trên cùng (chạy theo thanh trên khi nó tự ẩn), báo WebView
     * vùng nào nhường chạm.
     */
    private String lastLayout;
    private ZaloWebView lastWv;
    private static final String BOOT_LAYOUT = "native_boot_layout";
    private static final String BOOT_ME = "native_boot_me"; // [tên, ảnh] tài khoản cho thanh trên tạm
    private boolean booting;
    private String savedBoot, savedMe;

    /**
     * Mở app khi trang Zalo còn đang tải ("Đang đăng nhập…"): hiện ngay danh sách đã lưu, nằm trên WebView, cùng màu
     * và vị trí như lần trước (bố cục cuối lưu lại). Chưa mở được hội thoại nên chạm dòng không làm gì. Trang xong
     * (boot(false)): trả danh sách về dưới WebView và áp bố cục thật. Trả false nếu chưa có gì để hiện.
     */
    boolean boot(boolean on) {
        if (on) {
            if (!hasData()) return false;
            booting = true;
            JSONArray me = null, tabs = null;
            try {
                JSONObject o = new JSONObject(Prefs.sp(getContext()).getString(BOOT_LAYOUT, "{}"));
                int color = parseCss(o.optString("bg")), c1 = parseCss(o.optString("fg")), c2 = parseCss(o.optString("fg2"));
                applyColors(color, c1, c2);
                animateTop(dp(o.optInt("top", 96)));
                tabs = o.optJSONArray("tabs");
                placeGlass(o.optJSONArray("dock"), false); // kính mờ dưới dock native ngay lúc mở app
            } catch (Exception e) {
                animateTop(dp(96));
            }
            try {
                me = new JSONArray(Prefs.sp(getContext()).getString(BOOT_ME, "[]"));
            } catch (Exception ignored) {
            }
            if (me != null) header.setMe(me.optString(0), me.optString(1));
            if (tabs != null) header.setTabs(tabs.optJSONArray(0), tabs.optInt(1), false);
            header.setConn("wait");
            setVisibility(VISIBLE);
            // Lên trên WebView bằng độ cao Z (vẽ + nhận chạm trước), không gỡ / gắn lại view: gắn lại thì cả danh sách
            // vẽ lại từ đầu, lúc trang xong màn hình nháy một cái.
            setOutlineProvider(null); // không đổ bóng
            setTranslationZ(1000);
            return true;
        }
        if (!booting) return false;
        // Trang báo xong khi danh sách web hiện, chế độ native của trang bật ngay sau đó: chờ bố cục "on" (setLayout)
        // để khỏi lộ danh sách web một nhịp; quá lâu thì thôi.
        if (!bootLayoutOn()) {
            // Chờ trang bật chế độ native (Zalo nạp xong tin cuối / danh bạ mới gửi danh sách, mục 12) — không có mốc
            // hết giờ, không lộ trang ra giữa chừng.
            bootEnding = true;
            return true;
        }
        endBoot();
        return true;
    }

    private boolean bootEnding;

    /**
     * Lúc chạm mở một hội thoại (uptime ms, 0: không có). Tới khi khung chat lên (bố cục báo pass = false) danh sách
     * không nhận chạm nữa, để chạm nhanh dòng khác không mở nhầm hội thoại thứ hai; quá 1,5 s (Zalo chưa mở được, vd. trang vừa tải) thì thôi khóa để chạm lại được.
     */
    private long openingAt;
    /** Vừa chạm mở một hội thoại (MainActivity: ẩn dock native ngay). */
    Runnable onOpen;

    private boolean opening() {
        return openingAt != 0 && android.os.SystemClock.uptimeMillis() - openingAt < 1500;
    }

    private long moreAt;

    /**
     * Cuộn tới cuối danh sách: nhờ trang nạp thêm hội thoại cũ (Zalo chỉ nạp khi danh sách web của nó cuộn tới đáy,
     * mà danh sách web nằm ẩn dưới danh sách này). Tối đa mỗi 2 s một lần.
     */
    private void loadMore() {
        if (booting) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - moreAt < 2000) return;
        moreAt = now;
        WebViewHolder.evalMain("window.__zw&&window.__zw.loadMoreConvs&&window.__zw.loadMoreConvs()");
    }

    /** Mở hội thoại ở dòng pos (chạm, hoặc Enter / bấm trackpad ở chế độ BlackBerry). */
    private void open(int pos) {
        if (opening() || pos < 0 || pos >= items.size()) return; // đang mở một hội thoại: bỏ qua chạm khác (chạm nhanh dòng thứ hai)
        Item it = items.get(pos);
        if (booting) { // trang chưa xong: nhớ lại, mở khi trang sẵn sàng (endBoot)
            pendingOpen = it.id;
            return;
        }
        openingAt = android.os.SystemClock.uptimeMillis();
        if (onOpen != null) onOpen.run(); // dock native mờ đi ngay, không chờ trang báo
        WebViewHolder.evalMain("window.__zw&&window.__zw.openConv&&window.__zw.openConv(" + JSONObject.quote(it.id) + ")");
    }

    // ------------------------------------------------------------------ bàn phím cứng, trackpad (BlackBerry Q20)

    /**
     * Hội thoại đang chọn bằng trackpad / phím mũi tên (theo mã, không theo vị trí: tin mới đẩy hội thoại lên đầu thì
     * vẫn chọn đúng hội thoại đó); null: không có (đang dùng cảm ứng).
     */
    private String keySelId;
    /** Chữ gõ ở danh sách để tìm kiếm, chờ ô tìm kiếm của trang được focus (onEditing) rồi mới chèn vào. */
    private final StringBuilder typed = new StringBuilder();
    private final Runnable dropTyped = () -> typed.setLength(0);

    /** Vị trí hội thoại đang chọn, -1 nếu không có / không còn trong danh sách. */
    private int keySelPos() {
        if (keySelId == null) return -1;
        for (int i = 0; i < items.size(); i++) if (keySelId.equals(items.get(i).id)) return i;
        return -1;
    }

    private void setKeySel(int pos) {
        String id = pos >= 0 && pos < items.size() ? items.get(pos).id : null;
        if (TextUtils.equals(id, keySelId)) return;
        keySelId = id;
        adapter.notifyDataSetChanged();
    }

    /** Danh sách đang hiện, nhận chạm (không bị khung chat / hộp thoại che) và trang không có ô nhập đang focus. */
    private boolean acceptsKeys() {
        return getVisibility() == VISIBLE && !booting && !items.isEmpty() && !WebViewHolder.editing
                && (lastWv == null || lastWv.passTop == 0);
    }

    /**
     * Phím cứng khi đang ở danh sách chat: trackpad / mũi tên lên xuống chọn dòng, Enter / bấm trackpad mở, Space /
     * Shift+Space cuộn một trang, gõ chữ / số là tìm kiếm (như "gõ để tìm" của BlackBerry). Trả false: phím không thuộc
     * danh sách, để hệ thống / trang xử lý.
     */
    boolean onKey(KeyEvent e) {
        if (typed.length() > 0 && e.getAction() == KeyEvent.ACTION_DOWN) { // đang chờ ô tìm kiếm: gom tiếp chữ gõ nhanh
            int ch = e.getUnicodeChar(e.getMetaState());
            if (ch > 0 && !Character.isISOControl(ch)) {
                typed.appendCodePoint(ch);
                return true;
            }
        }
        if (!acceptsKeys()) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveKeySel(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                int pos = keySelPos();
                if (pos < 0) return false;
                if (down && e.getRepeatCount() == 0) {
                    setKeySel(-1);
                    open(pos);
                }
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (down) {
                    int page = Math.max(dp(80), getHeight() - topPx - dp(80));
                    listView.smoothScrollBy(e.isShiftPressed() ? -page : page, 200);
                }
                return true;
            default:
                break;
        }
        if (!down || e.getRepeatCount() != 0 || e.isCtrlPressed() || e.isMetaPressed()) return false;
        int ch = e.getUnicodeChar(e.getMetaState());
        if (ch <= 0 || Character.isWhitespace(ch) || Character.isISOControl(ch)) return false;
        typed.setLength(0);
        typed.appendCodePoint(ch);
        removeCallbacks(dropTyped);
        postDelayed(dropTyped, 2000); // trang không mở được ô tìm kiếm: bỏ chữ đã gom
        setKeySel(-1);
        tap("search", 0);
        return true;
    }

    private void moveKeySel(int step) {
        int first = listView.getFirstVisiblePosition();
        int cur = keySelPos();
        int pos = cur < 0 ? Math.max(first, firstUnderHeader()) : Math.max(0, Math.min(items.size() - 1, cur + step));
        setKeySel(pos);
        // Giữ dòng chọn nằm dưới thanh trên (thanh trên của trang che phần đầu danh sách).
        int top = topPx + dp(4), bottom = getHeight() - dp(64 + Prefs.dockBottom(getContext())); // chừa dock nổi
        View v = listView.getChildAt(pos - first);
        if (v == null) {
            listView.setSelectionFromTop(pos, Math.round(top - listView.getTranslationY())); // danh sách đang dịch theo thanh trên
            return;
        }
        float y = v.getTop() + listView.getTranslationY();
        if (y < top) listView.smoothScrollBy(Math.round(y - top), 120);
        else if (y + v.getHeight() > bottom) listView.smoothScrollBy(Math.round(y + v.getHeight() - bottom), 120);
    }

    /** Dòng đầu tiên không bị thanh trên che. */
    private int firstUnderHeader() {
        int first = listView.getFirstVisiblePosition();
        for (int i = 0; i < listView.getChildCount(); i++) {
            View v = listView.getChildAt(i);
            if (v.getTop() + listView.getTranslationY() >= topPx - dp(8)) return first + i;
        }
        return first;
    }

    /** Ô nhập của trang vừa được focus (ô tìm kiếm mở do gõ chữ): chèn chữ đã gom. */
    void onEditing(boolean on) {
        if (!on || typed.length() == 0) return;
        String s = typed.toString();
        typed.setLength(0);
        removeCallbacks(dropTyped);
        WebViewHolder.evalMain("window.__zw&&window.__zw.typeText&&window.__zw.typeText(" + JSONObject.quote(s) + ")");
    }

    // ------------------------------------------------------------------ thanh trên native

    private Header header;
    private int tabCount;

    /**
     * Thanh trên + thanh lọc vẽ bằng app, luôn hiện cùng danh sách native (cả lúc trang đang tải): ảnh + tên tài khoản,
     * trạng thái kết nối, tìm kiếm / thêm bạn / tạo nhóm, tab Ưu tiên / Khác, Phân loại, "…". Thanh của trang vẫn còn
     * (ẩn, mục 12 của shim.js) và làm việc thật: chạm nút nào ở đây thì app bấm hộ nút tương ứng trên trang
     * (__zw.headTap), menu nào mở ra là menu của Zalo. Biểu tượng là font biểu tượng của Zalo gói trong app (ZaloIcons),
     * vị trí / cỡ như thanh của trang (#zd-head, mục 8) nên không phụ thuộc trang đã tải hay chưa.
     */
    private final class Header extends LinearLayout {
        final ImageView av;
        final TextView name, status;
        final View dot;
        final GradientDrawable dotBg = new GradientDrawable();
        final TextView[] icons = new TextView[3];
        final LinearLayout tabs;
        final FrameLayout tabRow;
        final View indicator, line;
        final TextView cat, chev, more;
        android.animation.ObjectAnimator blink;
        String me = "", meAv = "", conn = "", cats = "";
        int sel = -1;

        Header(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setClickable(true); // chạm vào chỗ trống của thanh không lọt xuống danh sách
            LinearLayout bar = new LinearLayout(c);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(12), 0, dp(10), 0);
            addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            // Ảnh + tên + trạng thái: bấm là mở menu tài khoản như #zd-head của trang.
            LinearLayout who = new LinearLayout(c);
            who.setGravity(Gravity.CENTER_VERTICAL);
            who.setOnClickListener(v -> tap("head", 0));
            bar.addView(who, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            av = new ImageView(c);
            who.addView(av, new LayoutParams(dp(36), dp(36)));
            LinearLayout txt = new LinearLayout(c);
            txt.setOrientation(VERTICAL);
            LayoutParams tl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.leftMargin = dp(10);
            who.addView(txt, tl);
            name = new TextView(c);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17 * zoom);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); // ~ font-weight 600 của web
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(name);
            LinearLayout st = new LinearLayout(c);
            st.setGravity(Gravity.CENTER_VERTICAL);
            dot = new View(c);
            dotBg.setShape(GradientDrawable.OVAL);
            dot.setBackground(dotBg);
            st.addView(dot, new LayoutParams(dp(7), dp(7)));
            status = new TextView(c);
            status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12 * zoom);
            LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin = dp(5);
            st.addView(status, sl);
            txt.addView(st);
            // Tìm kiếm (14px phóng 1.2 như shim.js), thêm bạn, tạo nhóm (20px), mỗi nút 32px. WebView phóng cả font biểu
            // tượng theo "Cỡ chữ" nên nhân zoom như chữ.
            String[] ic = {"search", "outline-add-new-contact-2", "Group_Create_24_Line"};
            String[] act = {"search", "add", "group"};
            float[] sz = {14 * 1.2f, 20, 20};
            for (int i = 0; i < 3; i++) {
                final String a = act[i];
                icons[i] = ZaloIcons.view(c, ic[i], sz[i] * zoom, fg);
                icons[i].setOnClickListener(v -> tap(a, 0));
                LayoutParams bl = new LayoutParams(dp(32), dp(32));
                bl.leftMargin = dp(4);
                bar.addView(icons[i], bl);
            }
            // Hàng tab + Phân loại + "…" (31px) và đường kẻ dưới (như .msg-filters-bar).
            tabRow = new FrameLayout(c);
            addView(tabRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(8), 0);
            tabRow.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tabs = new LinearLayout(c);
            row.addView(tabs, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(new View(c), new LayoutParams(0, 1, 1f));
            LinearLayout catBox = new LinearLayout(c);
            catBox.setGravity(Gravity.CENTER_VERTICAL);
            catBox.setOnClickListener(v -> tap("cat", 0));
            row.addView(catBox, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cat = new TextView(c);
            cat.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14 * zoom);
            cat.setSingleLine();
            catBox.addView(cat);
            chev = ZaloIcons.view(c, "icon-solid-down", 16 * zoom, fg);
            LayoutParams cl = new LayoutParams(Math.round(dp(16) * zoom), Math.round(dp(16) * zoom));
            cl.leftMargin = dp(4);
            catBox.addView(chev, cl);
            more = ZaloIcons.view(c, "solid-more", 16 * zoom, fg);
            more.setOnClickListener(v -> tap("more", 0));
            LayoutParams ml = new LayoutParams(dp(24), dp(24));
            ml.leftMargin = dp(8);
            ml.rightMargin = dp(9);
            row.addView(more, ml);
            indicator = new View(c);
            indicator.setBackgroundColor(0xFF0068FF);
            indicator.setPivotX(0);
            tabRow.addView(indicator, new FrameLayout.LayoutParams(dp(10), dp(2), Gravity.BOTTOM | Gravity.START));
            indicator.setVisibility(INVISIBLE);
            line = new View(c);
            addView(line, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(.5f))));
            setCats("Phân loại");
            setConn("wait");
            applyColors();
        }

        void applyColors() {
            setBackgroundColor(bg);
            name.setTextColor(fg);
            status.setTextColor(fg2);
            cat.setTextColor(fg);
            chev.setTextColor(fg);
            more.setTextColor(fg);
            for (TextView t : icons) t.setTextColor(fg);
            line.setBackgroundColor(Color.luminance(bg) < 0.4f ? 0x22FFFFFF : 0x1F000000);
            for (int i = 0; i < tabs.getChildCount(); i++) ((TextView) tabs.getChildAt(i)).setTextColor(i == sel ? 0xFF0068FF : fg2);
        }

        void setMe(String n, String url) {
            if (n == null || n.isEmpty() || (n.equals(me) && url.equals(meAv))) return;
            me = n;
            meAv = url;
            name.setText(n);
            av.setImageBitmap(Avatars.placeholder(n, false, dp(36)));
            if (!url.isEmpty()) Avatars.load(getContext(), url, dp(36), b -> { if (b != null && url.equals(meAv)) av.setImageBitmap(b); });
        }

        /** on / wait / off — như nhãn dưới tên của trang (mục 8): xanh, vàng nhấp nháy, đỏ. */
        void setConn(String s) {
            if (s == null || s.equals(conn)) return;
            conn = s;
            boolean wait = "wait".equals(s);
            dotBg.setColor("on".equals(s) ? 0xFF30A46C : wait ? 0xFFF5A524 : 0xFFE5484D);
            status.setText("on".equals(s) ? "Đã kết nối" : wait ? "Đang kết nối" : "Mất kết nối");
            if (wait && blink == null) {
                blink = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, .15f);
                blink.setDuration(550);
                blink.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                blink.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                blink.start();
            } else if (!wait && blink != null) {
                blink.cancel();
                blink = null;
                dot.setAlpha(1f);
            }
        }

        void setCats(String s) {
            if (s == null || s.isEmpty() || s.equals(cats)) return;
            cats = s;
            cat.setText(s);
        }

        /** Tên các tab + tab đang chọn; đổi tab thì gạch chân trượt sang (animate). */
        void setTabs(JSONArray names, int selected, boolean animate) {
            if (names == null) return;
            String key = names.toString();
            if (!key.equals(tabs.getTag())) {
                tabs.setTag(key);
                tabs.removeAllViews();
                for (int i = 0; i < names.length(); i++) {
                    final int idx = i;
                    TextView t = new TextView(getContext());
                    t.setText(names.optString(i));
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(dp(8), 0, dp(8), 0);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14 * zoom);
                    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    t.setOnClickListener(v -> tapTab(idx));
                    tabs.addView(t, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                tabCount = names.length();
                sel = -1;
                animate = false;
            }
            select(selected, animate);
        }

        void select(int selected, boolean animate) {
            if (selected < 0 || selected >= tabs.getChildCount()) return;
            boolean changed = selected != sel;
            sel = selected;
            for (int i = 0; i < tabs.getChildCount(); i++) ((TextView) tabs.getChildAt(i)).setTextColor(i == sel ? 0xFF0068FF : fg2);
            if (!changed && indicator.getVisibility() == VISIBLE) return;
            final boolean anim = animate && indicator.getVisibility() == VISIBLE;
            tabs.post(() -> moveIndicator(anim));
        }

        private void moveIndicator(boolean animate) {
            View t = tabs.getChildAt(sel);
            if (t == null || t.getWidth() == 0) return;
            float x = tabs.getLeft() + t.getLeft() + dp(8), w = t.getWidth() - dp(16);
            float base = indicator.getLayoutParams().width;
            indicator.setVisibility(VISIBLE);
            indicator.animate().cancel();
            if (!animate) {
                indicator.setTranslationX(x);
                indicator.setScaleX(w / base);
                return;
            }
            indicator.animate().translationX(x).scaleX(w / base).setDuration(320)
                    .setInterpolator(new android.view.animation.PathInterpolator(.2f, .9f, .25f, 1f)).start();
        }
    }

    /** Chạm một nút trên thanh native: bấm hộ nút tương ứng của trang (mục 12 của shim.js). */
    private void tap(String what, int index) {
        if (booting) return; // trang chưa sẵn sàng
        WebViewHolder.evalMain("window.__zw&&window.__zw.headTap&&window.__zw.headTap('" + what + "'," + index + ")");
        if ("search".equals(what) && lastWv != null) {
            // Tìm kiếm cần bàn phím: ô nhập là của trang, đưa focus vào WebView rồi mở bàn phím.
            final ZaloWebView wv = lastWv;
            wv.requestFocus();
            postDelayed(() -> {
                android.view.inputmethod.InputMethodManager im = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (im != null) im.showSoftInput(wv, 0);
            }, 200);
        }
    }

    /** Chạm tab: gạch chân + trang danh sách trượt ngay (không chờ trang đổi tab), rồi bấm hộ tab của trang. */
    private void tapTab(int idx) {
        if (booting || header == null || idx == header.sel) return;
        boolean forward = idx > header.sel;
        header.select(idx, true);
        startTabSlide(forward);
        tabSel = idx;
        tap("tab", idx);
    }

    /** Trạng thái kết nối máy chủ Zalo (JsBridge.connState). */
    void setConn(String state) {
        if (header != null) header.setConn(state);
    }

    /** Trang Zalo bắt đầu tải lại: trang mới chưa biết danh sách native đã sẵn sàng, báo lại ở lần nhận dữ liệu tới. */
    void onPageReload() {
        announced = false;
    }

    private boolean bootLayoutOn() {
        try {
            return lastLayout != null && new JSONObject(lastLayout).optBoolean("on");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean endingBoot;

    /** Trang cần người dùng (màn đăng nhập): bỏ lớp che ngay, ẩn danh sách. */
    void cancelBoot() {
        if (!booting) return;
        booting = false;
        bootEnding = false;
        endingBoot = false;
        setTranslationZ(0);
        setVisibility(GONE);
        if (lastWv != null) lastWv.setBackgroundColor(MainActivity.pageBottomColor());
    }

    /**
     * Trang xong: trả danh sách về dưới WebView. WebView áp nền trong suốt qua tiến trình vẽ của Chromium, chậm ít nhất
     * một khung hình — đổi lớp ngay thì khung đó WebView còn nền đặc phủ kín màn hình (chớp cả màn). Nên đặt nền trong
     * suốt trước, chờ vài khung rồi mới đổi lớp.
     */
    private void endBoot() {
        if (!booting || endingBoot) return;
        endingBoot = true;
        if (lastWv != null) lastWv.setBackgroundColor(Color.TRANSPARENT);
        postDelayed(this::finishBoot, 150);
    }

    private void finishBoot() {
        endingBoot = false;
        if (!booting) return;
        booting = false;
        bootEnding = false;
        setTranslationZ(0); // về lại dưới WebView (thanh trên native vẫn là của danh sách này, không đổi gì)
        WebViewHolder.evalMain("window.__zw&&window.__zw.connReport&&window.__zw.connReport()");
        if (lastLayout != null) setLayout(lastLayout, lastWv);
        else setVisibility(GONE);
        if (pendingOpen != null) {
            WebViewHolder.evalMain("window.__zw&&window.__zw.openConv&&window.__zw.openConv(" + JSONObject.quote(pendingOpen) + ")");
            pendingOpen = null;
        }
    }

    private String pendingOpen; // hội thoại được chạm lúc trang còn đang tải

    // ------------------------------------------------------------------ trượt trang khi đổi tab lọc

    private int tabSel = -1;
    private String tabNames = "";
    private long shownAt;
    private android.animation.ValueAnimator slideAnim; // trượt ngang riêng: animate() của danh sách đang lo trượt dọc
    private ImageView slideOld; // ảnh danh sách cũ đang trượt ra
    private boolean slideForward;
    private final Runnable slideRun = this::runTabSlide;

    /**
     * Đổi tab trên thanh lọc của trang (Ưu tiên ↔ Khác...): chụp danh sách cũ đặt đè lên, danh sách thật dời ra ngoài
     * mép màn hình chờ dữ liệu tab mới (setData) rồi cả hai cùng trượt — như lật trang. Tab bên phải: trượt sang trái.
     */
    private void startTabSlide(boolean forward) {
        int w = listView.getWidth(), h = listView.getHeight();
        if (w == 0 || h == 0) return;
        if (slideAnim != null) slideAnim.cancel(); // lần trượt trước đang chạy: kết thúc nó (onAnimationEnd) trước
        endTabSlide();
        Bitmap shot;
        try {
            shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return;
        }
        Canvas cv = new Canvas(shot);
        cv.drawColor(bg);
        listView.draw(cv);
        slideOld = new ImageView(getContext());
        slideOld.setImageBitmap(shot);
        slideOld.setScaleType(ImageView.ScaleType.MATRIX);
        LayoutParams lp = new LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = listView.getLeft();
        lp.topMargin = listView.getTop();
        slideOld.setTranslationY(listView.getTranslationY());
        addView(slideOld, indexOfChild(listView) + 1, lp);
        slideForward = forward;
        listView.setTranslationX(forward ? w : -w);
        removeCallbacks(slideRun);
        postDelayed(slideRun, 250); // dữ liệu tab mới chưa tới (tab trống, máy chậm...): vẫn trượt cùng nhịp với tab
    }

    private void runTabSlide() {
        removeCallbacks(slideRun);
        final ImageView old = slideOld;
        if (old == null || (slideAnim != null && slideAnim.isRunning())) return;
        final int w = listView.getWidth();
        final float from = listView.getTranslationX();
        slideAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        slideAnim.setDuration(320);
        slideAnim.setInterpolator(new android.view.animation.PathInterpolator(.2f, .9f, .25f, 1f));
        slideAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            listView.setTranslationX(from * (1 - f));
            old.setTranslationX((slideForward ? -w : w) * f);
            glass.invalidate();
        });
        slideAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                listView.setTranslationX(0);
                endTabSlide();
            }
        });
        slideAnim.start();
    }

    private void endTabSlide() {
        ImageView old = slideOld;
        slideOld = null;
        if (old == null) return;
        removeView(old);
        android.graphics.drawable.Drawable dr = old.getDrawable();
        if (dr instanceof android.graphics.drawable.BitmapDrawable) ((android.graphics.drawable.BitmapDrawable) dr).getBitmap().recycle();
    }

    // ------------------------------------------------------------------ kính mờ của dock

    private DockGlass glass;
    /** Bản ghi nội dung danh sách (Android 12+), kính vẽ lại nút này. */
    private android.graphics.RenderNode content;

    /** Lớp kính mờ cho dock native (NativeDock.setGlass): vẽ lại phần danh sách nằm sau dock rồi làm nhòe. */
    View dockGlass() {
        return glass;
    }

    /**
     * Kính chỉ có khi danh sách native đang hiện (tab Tin nhắn) và máy làm nhòe được (RenderEffect, Android 12+); các tab
     * khác (Danh bạ...) là trang web, dock của trang tự làm nhòe (backdrop-filter). Kính nằm trong dock native nên ẩn /
     * hiện, trượt cùng một lớp với dock — không lệch nhịp.
     */
    private void placeGlass(JSONArray d, boolean hidden) {
        if (glass == null) return;
        glass.setVisibility(d != null && android.os.Build.VERSION.SDK_INT >= 31 ? VISIBLE : GONE);
        glass.invalidate();
    }

    private final class DockGlass extends View {
        private final int[] at = new int[2], from = new int[2];

        DockGlass(Context c) {
            super(c);
            setWillNotDraw(false);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() / 2f);
                }
            });
            setClipToOutline(true);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
                sat.setSaturation(1.7f);
                float r = 28 * getResources().getDisplayMetrics().density;
                setRenderEffect(android.graphics.RenderEffect.createChainEffect(
                        android.graphics.RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(sat)),
                        android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)));
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(bg);
            // Vị trí thật trên màn hình của danh sách so với kính (kính đi theo dock, danh sách dịch theo thanh trên).
            listView.getLocationInWindow(at);
            getLocationInWindow(from);
            int save = c.save();
            c.translate(at[0] - from[0], at[1] - from[1]);
            if (content != null && c.isHardwareAccelerated()) c.drawRenderNode(content);
            c.restoreToCount(save);
        }
    }

    void setLayout(String json, ZaloWebView wv) {
        lastLayout = json;
        lastWv = wv;
        if (booting) { // đang che trang lúc tải (boot): áp bố cục thật khi trang xong
            // Trang báo danh sách native không dùng được (không đọc được hội thoại của bản Zalo này): chế độ native sẽ
            // không bật, đừng chờ — bỏ lớp che để thấy danh sách của Zalo Web.
            if (json.contains("\"nb\":true")) {
                DiagLog.add("native list unusable, showing web list");
                cancelBoot();
                return;
            }
            if (bootLayoutOn() && wv != null) wv.setBackgroundColor(Color.TRANSPARENT); // trước khi đổi lớp (endBoot)
            if (bootEnding && bootLayoutOn()) endBoot();
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            boolean on = o.optBoolean("on");
            // Lưu cho lần mở sau (boot) khi thanh trên hiện đủ (thanh tìm kiếm 64 + thanh lọc 32px; cuộn thì lùi 64px).
            JSONArray me = o.optJSONArray("me");
            if (me != null && !me.optString(0).isEmpty() && !me.toString().equals(savedMe)) {
                savedMe = me.toString();
                Prefs.sp(getContext()).edit().putString(BOOT_ME, savedMe).apply();
            }
            if (on && o.optBoolean("pass") && o.optInt("top") >= 80 && !json.equals(savedBoot)) {
                savedBoot = json;
                Prefs.sp(getContext()).edit().putString(BOOT_LAYOUT, json).apply();
            }
            boolean pass = on && o.optBoolean("pass", true); // chat / hộp thoại đang che: danh sách vẫn hiện, không nhận chạm
            if (!pass) openingAt = 0; // khung chat đã lên (WebView nhận chạm): hết khóa
            int top = dp(o.optInt("top"));
            JSONArray d = o.optJSONArray("dock");
            int color = parseCss(o.optString("bg")), c1 = parseCss(o.optString("fg")), c2 = parseCss(o.optString("fg2"));
            if ((color != 0 && color != bg) || (c1 != 0 && c1 != fg) || (c2 != 0 && c2 != fg2)) applyColors(color, c1, c2);
            JSONArray tabs = o.optJSONArray("tabs");
            int sel = tabs == null ? -1 : tabs.optInt(1, -1);
            if (me != null) header.setMe(me.optString(0), me.optString(1));
            if (tabs != null) header.setTabs(tabs.optJSONArray(0), sel, true);
            header.setCats(o.optString("cat"));
            String names = tabs == null ? "" : String.valueOf(tabs.optJSONArray(0));
            // Chỉ khi người dùng đổi tab (cùng bộ tab, đang ở danh sách, không bị che) — lúc trang dựng xong Zalo cũng đổi
            // tab đang chọn, không trượt khi đó.
            if (on && pass && getVisibility() == VISIBLE && tabSel >= 0 && sel >= 0 && sel != tabSel && names.equals(tabNames)
                    && android.os.SystemClock.uptimeMillis() - shownAt > 1500) {
                startTabSlide(sel > tabSel);
            }
            if (sel >= 0) { tabSel = sel; tabNames = names; }
            if (on && getVisibility() != VISIBLE) shownAt = android.os.SystemClock.uptimeMillis();            setVisibility(on ? VISIBLE : GONE);
            placeGlass(on ? d : null, o.optBoolean("dh"));
            if (wv != null) {
                wv.passTop = pass ? 0 : -1; // cả thanh trên (native) lẫn danh sách nhận chạm, trừ dock của trang
                wv.passExclude = on && d != null ? new int[]{dp(d.optInt(0)), dp(d.optInt(1)), dp(d.optInt(2)), dp(d.optInt(3))} : null;
                wv.setBackgroundColor(on ? Color.TRANSPARENT : MainActivity.pageBottomColor());
            }
            if (on) animateTop(top);
        } catch (Exception e) {
            Log.w(WebViewHolder.TAG, "bad native layout", e);
        }
    }

    /**
     * Thanh trên của web tự ẩn / hiện (top đổi giữa mép dưới thanh và 0). Khoảng trống trên cùng của danh sách giữ cố
     * định bằng chiều cao đầy đủ của thanh (fullTop); cả danh sách dịch lên/xuống theo thanh (translationY), như trang
     * web dịch cột danh sách. Đổi khoảng trống thì ListView giữ nguyên vị trí dòng đầu: thanh hiện lại sẽ che mất dòng
     * đầu (vuốt mạnh lên tới đầu chỉ thấy từ dòng thứ hai).
     */
    private int fullTop;

    private void animateTop(int top) {
        if (top > fullTop) {
            fullTop = top;
            // Danh sách cao hơn khung đúng fullTop (lề dưới âm) để lúc dịch lên không hở đáy.
            LayoutParams lp = (LayoutParams) listView.getLayoutParams();
            lp.bottomMargin = -fullTop;
            listView.setLayoutParams(lp);
            // Chừa chỗ cuối cho dock nổi (56px + cách đáy) + phần danh sách lòi dưới đáy khi thanh đang hiện.
            listView.setPadding(0, fullTop, 0, fullTop + dp(64 + Prefs.dockBottom(getContext())));
        }
        if (top == topPx) return;
        topPx = top;
        float y = top - fullTop;
        // Thanh trên dịch theo chiều cao thật của nó (thanh 64 + hàng tab + kẻ), không theo fullTop: fullTop chỉ tăng (vd.
        // Zalo từng hiện thêm băng thông báo đẩy thanh lọc xuống) — dịch theo nó thì thanh trên lấn lên thanh trạng thái.
        int hh = header.getHeight() > 0 ? header.getHeight() : dp(96);
        float hy = Math.max(-dp(64), Math.min(0, top - hh));
        listView.animate().cancel();
        header.animate().cancel();
        if (getVisibility() != VISIBLE) {
            listView.setTranslationY(y);
            header.setTranslationY(hy);
        } else {
            android.view.animation.PathInterpolator ease = new android.view.animation.PathInterpolator(.22f, .61f, .36f, 1f);
            listView.animate().translationY(y).setDuration(420) // cùng nhịp với dock của web
                    .setUpdateListener(a -> glass.invalidate())
                    .setInterpolator(ease).start();
            header.animate().translationY(hy).setDuration(420).setInterpolator(ease).start(); // chừa hàng tab
        }
    }

    private static int parseCss(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)").matcher(s);
        return m.find() ? Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) : 0;
    }

    /** Màu nền + chữ chính + chữ phụ lấy từ danh sách web; thiếu màu chữ thì suy theo nền sáng/tối. */
    private void applyColors(int color, int c1, int c2) {
        if (color != 0) bg = color;
        boolean dark = Color.luminance(bg) < 0.4f;
        fg = c1 != 0 ? c1 : dark ? 0xFFE6E8EB : 0xFF081B3A;
        fg2 = c2 != 0 ? c2 : dark ? 0xFF9AA4B2 : 0xFF5A6981;
        setBackgroundColor(bg);
        adapter.notifyDataSetChanged();
        if (header != null) header.applyColors();
    }

    /** Biểu tượng loại tin (MESSAGE_ICON của Zalo) → biểu tượng vector của app; 0 nếu không có. */
    private static int iconRes(String ic) {
        if (ic == null || ic.isEmpty()) return 0;
        if (ic.contains("picture") || ic.contains("doodle")) return R.drawable.zd_ic_image;
        if (ic.contains("video")) return R.drawable.zd_ic_video;
        if (ic.contains("file") || ic.contains("folder")) return R.drawable.zd_ic_file;
        if (ic.contains("sticker") || ic.equals("combined-shape")) return R.drawable.zd_ic_sticker;
        if (ic.contains("link")) return R.drawable.zd_ic_link;
        if (ic.contains("voice")) return R.drawable.zd_ic_mic;
        if (ic.contains("Call")) return R.drawable.zd_ic_phone;
        if (ic.contains("location")) return R.drawable.zd_ic_location;
        return 0;
    }

    // ------------------------------------------------------------------ dòng

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Row r = convert instanceof Row ? (Row) convert : new Row(getContext());
            r.bind(items.get(pos));
            // Dòng đang chọn bằng trackpad: nền nhạt theo màu chữ (sáng / tối đều thấy) + vạch xanh bên trái.
            r.setBackground(keySelId != null && keySelId.equals(items.get(pos).id) ? keySelBg() : null);
            return r;
        }
    }

    private android.graphics.drawable.Drawable keySelBg() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor((fg & 0x00FFFFFF) | 0x1A000000);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(0xFF0068FF);
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{fill, bar});
        d.setLayerWidth(1, dp(3));
        d.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        return d;
    }

    /** Một dòng như Zalo Web: cao 78, ảnh 48, cách lề 16, tên 16px, giờ 12px, tin cuối 14px (Q20: cao 62, ảnh 40, lề 12). */
    private final class Row extends LinearLayout {
        final ImageView avatar, mutedIc, msgIc, pinIc;
        final TextView name, time, text, badge;
        String boundAvatar, boundId;
        boolean hasRealAvatar;

        /** Ảnh thật đã nạp: thay ảnh đang hiện (ảnh cũ của cùng hội thoại thì chuyển mờ dần sang, không giật). */
        void setAvatar(Bitmap b) {
            android.graphics.drawable.Drawable cur = avatar.getDrawable();
            if (hasRealAvatar && cur != null && isShown()) {
                android.graphics.drawable.TransitionDrawable t = new android.graphics.drawable.TransitionDrawable(
                        new android.graphics.drawable.Drawable[]{cur, new android.graphics.drawable.BitmapDrawable(getResources(), b)});
                t.setCrossFadeEnabled(true);
                avatar.setImageDrawable(t);
                t.startTransition(200);
            } else {
                avatar.setImageBitmap(b);
            }
            hasRealAvatar = true;
        }

        private ImageView icon(Context c, int res, int sizeDp) {
            ImageView v = new ImageView(c);
            v.setImageResource(res);
            v.setLayoutParams(new LayoutParams(dp(sizeDp), dp(sizeDp)));
            return v;
        }

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int side = q20 ? 12 : 16;
            setPadding(dp(side), dp(q20 ? 3 : 4), dp(side), dp(q20 ? 3 : 4));
            setMinimumHeight(dp(q20 ? 62 : 78));
            avatar = new ImageView(c);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(avatar, new LayoutParams(dp(rowAv), dp(rowAv)));
            mutedIc = icon(c, R.drawable.zd_ic_muted, 14);
            msgIc = icon(c, R.drawable.zd_ic_image, 16);
            pinIc = icon(c, R.drawable.zd_ic_pin, 16);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            LayoutParams colLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            colLp.leftMargin = dp(q20 ? 10 : 12);
            addView(col, colLp);

            LinearLayout line1 = new LinearLayout(c);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            name = text(c, 16);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            line1.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            ((LayoutParams) mutedIc.getLayoutParams()).leftMargin = dp(6);
            line1.addView(mutedIc);
            time = text(c, 12);
            LayoutParams tLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tLp.leftMargin = dp(8);
            line1.addView(time, tLp);
            col.addView(line1);

            LinearLayout line2 = new LinearLayout(c);
            line2.setGravity(Gravity.CENTER_VERTICAL);
            ((LayoutParams) msgIc.getLayoutParams()).rightMargin = dp(4);
            line2.addView(msgIc);
            text = text(c, 14);
            text.setSingleLine();
            text.setEllipsize(TextUtils.TruncateAt.END);
            line2.addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            ((LayoutParams) pinIc.getLayoutParams()).leftMargin = dp(8);
            line2.addView(pinIc);
            badge = text(c, 11);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(20));
            badge.setPadding(dp(6), 0, dp(6), 0);
            LayoutParams bLp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(20));
            bLp.leftMargin = dp(8);
            line2.addView(badge, bLp);
            LayoutParams l2 = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            l2.topMargin = dp(q20 ? 3 : 4);
            col.addView(line2, l2);
        }

        private TextView text(Context c, int px) {
            TextView t = new TextView(c);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, px * zoom); // px CSS của web × "Cỡ chữ" của app
            t.setIncludeFontPadding(false);
            return t;
        }

        void bind(Item it) {
            boolean unread = it.unread > 0 || it.mention > 0;
            name.setText(it.name);
            name.setTextColor(fg);
            name.setTypeface(Typeface.DEFAULT, unread ? Typeface.BOLD : Typeface.NORMAL);
            time.setText(formatTime(it.time));
            time.setTextColor(unread ? fg : fg2);
            mutedIc.setVisibility(it.muted ? VISIBLE : GONE);
            mutedIc.setColorFilter(fg2);
            int ic = iconRes(it.icon);
            msgIc.setVisibility(ic != 0 ? VISIBLE : GONE);
            if (ic != 0) {
                msgIc.setImageResource(ic);
                msgIc.setColorFilter(it.missCall ? 0xFFE5383B : unread ? fg : fg2);
            }
            pinIc.setVisibility(it.pin > 0 && !unread ? VISIBLE : GONE);
            pinIc.setColorFilter(fg2);
            text.setText(it.text);
            text.setTextColor(unread ? fg : fg2);
            text.setTypeface(Typeface.DEFAULT, unread && !it.muted ? Typeface.BOLD : Typeface.NORMAL);
            if (unread) {
                // Như web: đỏ có số (quá 5 thì "5+"), tắt thông báo thì xám; được nhắc tên thì "@".
                badge.setVisibility(VISIBLE);
                badge.setText(it.mention > 0 ? "@" : it.unread > 5 ? "5+" : String.valueOf(it.unread));
                badge.setMinWidth(dp(20));
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(dp(10));
                d.setColor(it.muted ? 0xFF9AA4B2 : 0xFFE5383B);
                badge.setBackground(d);
            } else if (it.unreadDot > 0) { // chưa đọc không đếm (vd. Official Account): chấm đỏ
                badge.setVisibility(VISIBLE);
                badge.setText("");
                badge.setMinWidth(dp(10));
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setSize(dp(10), dp(10));
                d.setColor(0xFFE5383B);
                badge.setBackground(d);
            } else {
                badge.setVisibility(GONE);
            }
            String avKey = it.id + it.avatar + (it.members.length > 0 ? String.join(",", it.members) + it.total + bg : "");
            if (!TextUtils.equals(boundAvatar, avKey)) {
                // Cùng hội thoại, đã có ảnh thật (đổi link ảnh / thứ tự thành viên lúc nạp lại sau khi kết nối): giữ ảnh
                // cũ tới khi ảnh mới xong, không chớp qua vòng tròn chữ cái.
                boolean keep = TextUtils.equals(boundId, it.id) && hasRealAvatar;
                boundAvatar = avKey;
                boundId = it.id;
                if (!keep) {
                    avatar.setImageBitmap(Avatars.placeholder(it.name, it.me, dp(rowAv)));
                    hasRealAvatar = false;
                }
                if (it.group && TextUtils.isEmpty(it.avatar) && it.members.length > 0) {
                    final String key = boundAvatar;
                    boolean dark = Color.luminance(bg) < 0.4f;
                    Avatars.loadGroup(getContext(), it.members, it.total, dp(rowAv), bg, dark ? 0xFF3A3F46 : 0xFFE5E9EF,
                            dark ? 0xFFE6E8EB : 0xFF5A6981, b -> {
                                if (b != null && TextUtils.equals(key, boundAvatar)) setAvatar(b);
                                glass.invalidate();
                            });
                } else if (!TextUtils.isEmpty(it.avatar)) {
                    final String key = boundAvatar;
                    Avatars.load(getContext(), it.avatar, dp(rowAv), b -> {
                        if (b != null && TextUtils.equals(key, boundAvatar)) setAvatar(b);
                        glass.invalidate();
                    });
                }
            }
        }
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance(), t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        long diff = now.getTimeInMillis() - ms;
        if (diff < 60_000) return "Vài giây";
        if (diff < 3_600_000) return (diff / 60_000) + " phút";
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return (diff / 3_600_000) + " giờ";
        }
        // Số ngày theo lịch (không theo 24 giờ): hôm qua → "Hôm qua", 2–6 ngày trước → "N ngày".
        Calendar d0 = (Calendar) now.clone(), d1 = (Calendar) t.clone();
        for (Calendar cal : new Calendar[]{d0, d1}) {
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        }
        long days = Math.round((d0.getTimeInMillis() - d1.getTimeInMillis()) / 86_400_000.0);
        if (days <= 1) return "Hôm qua";
        if (days < 7) return days + " ngày";
        return new SimpleDateFormat("dd/MM", Locale.US).format(new Date(ms));
    }

    // ------------------------------------------------------------------ ảnh đại diện

    /** Ảnh đại diện tròn: bộ nhớ đệm trong RAM + file trong cache của app; tải bằng luồng nền. */
    static final class Avatars {
        interface Done {
            void loaded(Bitmap b);
        }

        private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount();
            }
        };
        /** Ảnh đang tải → các nơi đang chờ (cùng ảnh, cùng cỡ chỉ tải một lần, xong thì báo hết). */
        private static final Map<String, List<Done>> WAITING = new HashMap<>();
        private static final int[] COLORS = {0xFF4F8EF7, 0xFF2EB67D, 0xFFF29D38, 0xFFB05CF0, 0xFFE5585D, 0xFF2BB3C0};

        static void load(Context c, final String url, final int size, final Done done) {
            final String key = url + "@" + size;
            Bitmap hit = MEM.get(key);
            if (hit != null) {
                done.loaded(hit);
                return;
            }
            synchronized (WAITING) {
                List<Done> w = WAITING.get(key);
                if (w != null) { // đang tải: chờ cùng
                    w.add(done);
                    return;
                }
                w = new ArrayList<>();
                w.add(done);
                WAITING.put(key, w);
            }
            final Context app = c.getApplicationContext();
            App.IO.execute(() -> {
                Bitmap b = null;
                try {
                    File dir = new File(app.getCacheDir(), "avatars");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
                    if (!f.exists()) {
                        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                        con.setConnectTimeout(10_000);
                        con.setReadTimeout(15_000);
                        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                            byte[] buf = new byte[16 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } finally {
                            con.disconnect();
                        }
                    }
                    Bitmap raw = BitmapFactory.decodeFile(f.getPath());
                    if (raw != null) b = circle(raw, size);
                    //noinspection ResultOfMethodCallIgnored
                    else f.delete(); // tải dở / hỏng: lần sau tải lại
                } catch (Exception e) {
                    Log.d(WebViewHolder.TAG, "avatar load failed", e);
                }
                final Bitmap out = b;
                if (out != null) MEM.put(key, out);
                final List<Done> waiters;
                synchronized (WAITING) {
                    waiters = WAITING.remove(key);
                }
                App.MAIN.post(() -> {
                    if (waiters != null) for (Done d : waiters) d.loaded(out);
                });
            });
        }

        /**
         * Nhóm không có ảnh riêng, như Zalo: lưới 2×2 — ảnh 3 thành viên, ô cuối là tổng số người (có ít thành viên
         * thì chỉ chừng đó ô). Mỗi ô là vòng tròn nhỏ có viền màu nền để tách nhau.
         */
        static void loadGroup(Context c, final String[] urls, final int total, final int size, final int bg, final int countBg,
                              final int countFg, final Done done) {
            final int n = Math.min(3, urls.length);
            final int cell = Math.round(size * 0.54f);
            final Bitmap[] got = new Bitmap[n];
            final int[] left = {n};
            Runnable compose = () -> done.loaded(collage(got, total, size, cell, bg, countBg, countFg));
            if (n == 0) {
                compose.run();
                return;
            }
            for (int i = 0; i < n; i++) {
                final int k = i;
                load(c, urls[i], cell, b -> {
                    got[k] = b;
                    if (--left[0] == 0) compose.run();
                });
            }
        }

        private static Bitmap collage(Bitmap[] members, int total, int size, int cell, int bg, int countBg, int countFg) {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            int slots = members.length + (total > members.length ? 1 : 0);
            // Vị trí ô: 1 người ở giữa; 2 người chéo nhau; 3-4 ô xếp 2×2.
            int o = size - cell;
            int[][] pos = slots <= 1 ? new int[][]{{(size - cell) / 2, (size - cell) / 2}}
                    : slots == 2 ? new int[][]{{0, 0}, {o, o}}
                    : slots == 3 ? new int[][]{{(size - cell) / 2, 0}, {0, o}, {o, o}}
                    : new int[][]{{0, 0}, {o, 0}, {0, o}, {o, o}};
            float ring = Math.max(1.5f, size / 32f);
            for (int i = 0; i < slots && i < pos.length; i++) {
                float cx = pos[i][0] + cell / 2f, cy = pos[i][1] + cell / 2f;
                p.setColor(bg);
                cv.drawCircle(cx, cy, cell / 2f, p); // viền tách ô
                float r = cell / 2f - ring;
                if (i < members.length && members[i] != null) {
                    cv.drawBitmap(members[i], null, new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), p);
                } else {
                    p.setColor(i < members.length ? 0xFFC4CCD7 : countBg);
                    cv.drawCircle(cx, cy, r, p);
                    if (i >= members.length) { // ô tổng số người
                        p.setColor(countFg);
                        p.setTextAlign(Paint.Align.CENTER);
                        p.setTypeface(Typeface.DEFAULT_BOLD);
                        String s = total > 99 ? "99+" : String.valueOf(total);
                        p.setTextSize(r * (s.length() > 2 ? 0.75f : 0.95f));
                        cv.drawText(s, cx, cy - (p.descent() + p.ascent()) / 2, p);
                    }
                }
            }
            return out;
        }

        private static Bitmap circle(Bitmap src, int size) {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Path p = new Path();
            p.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            cv.clipPath(p);
            int s = Math.min(src.getWidth(), src.getHeight());
            android.graphics.Rect from = new android.graphics.Rect((src.getWidth() - s) / 2, (src.getHeight() - s) / 2,
                    (src.getWidth() + s) / 2, (src.getHeight() + s) / 2);
            cv.drawBitmap(src, from, new android.graphics.Rect(0, 0, size, size), new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        }

        /** Chưa có ảnh: vòng tròn màu với chữ cái đầu (My Documents: xanh, biểu tượng mây). */
        static Bitmap placeholder(String name, boolean cloud, int size) {
            String key = "ph:" + name + cloud + size;
            Bitmap hit = MEM.get(key);
            if (hit != null) return hit;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(cloud ? 0xFF0068FF : COLORS[Math.abs(name.hashCode()) % COLORS.length]);
            cv.drawCircle(size / 2f, size / 2f, size / 2f, p);
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(size * 0.38f);
            String initials = cloud ? "☁" : initials(name);
            cv.drawText(initials, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p);
            MEM.put(key, out);
            return out;
        }

        private static String initials(String name) {
            String[] parts = name.trim().split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String s : parts) {
                if (s.isEmpty() || !Character.isLetterOrDigit(s.codePointAt(0))) continue;
                sb.appendCodePoint(Character.toUpperCase(s.codePointAt(0)));
                if (sb.length() >= 2) break;
            }
            if (sb.length() == 0 && !name.isEmpty()) sb.appendCodePoint(name.codePointAt(0));
            return sb.toString();
        }
    }
}
