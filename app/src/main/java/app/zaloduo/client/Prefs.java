package app.zaloduo.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

/** Cài đặt người dùng, lưu trong SharedPreferences. */
final class Prefs {
    static final String KEEP_ALIVE = "keep_alive";
    static final String START_ON_BOOT = "start_on_boot";
    static final String CALL_ALERTS = "call_alerts";
    static final String MOBILE_UI = "mobile_ui";
    static final String VIEWPORT_WIDTH = "viewport_width";
    static final String TEXT_ZOOM = "text_zoom";
    static final String NOTIF_ASKED = "notif_asked";
    static final String BATTERY_ASKED = "battery_asked";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("zaloduo", Context.MODE_PRIVATE);
    }

    static boolean bool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    static int integer(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    static void put(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }

    static void put(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    static boolean keepAlive(Context c) { return bool(c, KEEP_ALIVE, true); }

    static boolean startOnBoot(Context c) { return bool(c, START_ON_BOOT, true); }

    /** Zalo Web không nghe gọi được: báo cuộc gọi đến (đổ chuông, toàn màn hình) đã bỏ. */
    static boolean callAlerts(Context c) { return false; }

    static final String DOCK_CLOUD = "dock_cloud", DOCK_TOOLS = "dock_tools";
    /** Nút zCloud / Công cụ của Zalo ở bên phải thanh dock (sau My Documents). */
    static boolean dockCloud(Context c) { return bool(c, DOCK_CLOUD, false); }
    static boolean dockTools(Context c) { return bool(c, DOCK_TOOLS, false); }


    /** Giao diện điện thoại: danh sách chat -> chạm mở chat -> Back quay lại. */
    static boolean mobileUi(Context c) { return bool(c, MOBILE_UI, true); }

    /** 0 = theo bề rộng màn hình điện thoại; khác 0 = ép trang hiển thị rộng bấy nhiêu px CSS. */
    static int viewportWidth(Context c) { return integer(c, VIEWPORT_WIDTH, 980); }

    /** Cỡ chữ (%) cho trang Zalo và giao diện native; lựa chọn "Theo máy" (lưu 0) = cỡ chữ trong Cài đặt của Android. */
    static int textZoom(Context c) {
        int v = textZoomSetting(c);
        if (v != 0) return v;
        float s = c.getResources().getConfiguration().fontScale;
        return Math.max(50, Math.min(200, Math.round(s * 100)));
    }

    /** Giá trị đã chọn trong Cài đặt: phần trăm, hoặc 0 = theo cỡ chữ của máy. */
    static int textZoomSetting(Context c) { return integer(c, TEXT_ZOOM, 100); }

    /** Độ đậm nền dock kính mờ (giao diện điện thoại), 0-100%: 0 = chỉ còn lớp làm mờ, 100 = nền đặc. */
    static final String DOCK_OPACITY = "dock_opacity";

    static int dockOpacity(Context c) { return integer(c, DOCK_OPACITY, 60); }

    /** Tự ẩn dock khi cuộn danh sách xuống, hiện lại khi cuộn lên. */
    static final String DOCK_AUTO_HIDE = "dock_auto_hide";

    static boolean dockAutoHide(Context c) { return bool(c, DOCK_AUTO_HIDE, true); }

    /** Danh sách chat vẽ bằng giao diện Android (NativeChatList) thay cho danh sách của Zalo Web. */
    static final String NATIVE_LIST = "native_list";

    static boolean nativeList(Context c) { return bool(c, NATIVE_LIST, true); }

    /** Khoảng cách từ dock tới đáy màn hình, px CSS. */
    static final String DOCK_BOTTOM = "dock_bottom";

    static int dockBottom(Context c) { return integer(c, DOCK_BOTTOM, q20(c) ? 8 : 24); }

    /**
     * Chế độ BlackBerry Q20 (Classic, bản thay main Android như Zinwa Q25): màn vuông 720x720 (~360x360 dp), bàn phím
     * cứng, trackpad. Danh sách chat gọn hơn, dock sát đáy, bảng biểu cảm thấp, điều khiển bằng phím. Mặc định tự nhận
     * theo màn hình (vuông và nhỏ — màn trong của máy gập cũng gần vuông nhưng rộng hơn 480 dp).
     */
    static final String Q20 = "q20";

    static boolean q20(Context c) { return bool(c, Q20, q20Detected(c)); }

    @SuppressWarnings("deprecation")
    static boolean q20Detected(Context c) {
        // Kích thước thật của màn hình (không theo cửa sổ: chia đôi màn hình trên điện thoại thường cũng ra gần vuông).
        android.hardware.display.DisplayManager dm = c.getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (d == null) return false;
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        if (w <= 0 || h <= 0 || m.density <= 0) return false;
        return Math.max(w, h) <= Math.min(w, h) * 1.25f && Math.min(w, h) / m.density < 480;
    }

    /** Màu nền trang Zalo ở mép trên/dưới lần gần nhất (sáng/tối), để mở app không bị chớp trắng. */
    static final String BAR_TOP = "bar_top";
    static final String BAR_BOTTOM = "bar_bottom";

    static int barTop(Context c) { return integer(c, BAR_TOP, 0xFFFFFFFF); }

    static int barBottom(Context c) { return integer(c, BAR_BOTTOM, 0xFFFFFFFF); }
}
