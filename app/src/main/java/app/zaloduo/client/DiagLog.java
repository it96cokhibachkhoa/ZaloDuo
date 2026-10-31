package app.zaloduo.client;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Nhật ký chẩn đoán thông báo: app vào nền/lên màn hình, màn hình, Doze, mạng, trang Zalo tải lại, Zalo bắn thông báo,
 * số tin chưa đọc... Mỗi dòng chỉ có thời gian và loại sự kiện, không có tên người hay nội dung tin nhắn.
 * Lưu ở bộ nhớ riêng của app, giữ khoảng 600 dòng gần nhất; sao chép từ màn hình Cài đặt.
 *
 * Nhật ký kết nối (tệp riêng, conn): chỉ chuyện giữ kết nối máy chủ Zalo — WebSocket mở / đóng (mã, thời gian sống,
 * lần nhận / gửi cuối), bộ đếm giờ của trang bị dừng, app vào nền, màn hình, Doze, mạng, tiết kiệm pin, hạn chế chạy
 * nền, hẹn giờ kiểm tra kết nối. Để tìm vì sao mất kết nối khi app ở nền.
 */
final class DiagLog {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final Ring MAIN = new Ring("diag.log", 600), CONN = new Ring("conn.log", 1500);

    private static Context app;

    private DiagLog() {}

    /** Một nhật ký: các dòng gần nhất trong bộ nhớ + tệp trong bộ nhớ riêng của app. */
    private static final class Ring {
        final String file;
        final int max;
        final ArrayDeque<String> lines = new ArrayDeque<>();
        boolean dirty;

        Ring(String file, int max) {
            this.file = file;
            this.max = max;
        }

        void load() {
            File f = new File(app.getFilesDir(), file);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String s;
                while ((s = r.readLine()) != null) push(s);
            } catch (IOException e) {
                Log.w(WebViewHolder.TAG, "read " + file, e);
            }
        }

        void push(String line) {
            lines.addLast(line);
            while (lines.size() > max) lines.removeFirst();
        }

        /** Gọi trong synchronized (DiagLog.class). */
        void add(String event) {
            synchronized (TIME) {
                push(TIME.format(new Date()) + "  " + event);
            }
            scheduleSave();
        }

        void scheduleSave() {
            if (dirty) return; // đã có lượt ghi file đang chờ, nó sẽ ghi cả dòng này
            dirty = true;
            App.IO.execute(this::save);
        }

        private void save() {
            String text;
            synchronized (DiagLog.class) {
                dirty = false;
                text = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
            }
            File f = new File(app.getFilesDir(), file);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (IOException e) {
                Log.w(WebViewHolder.TAG, "save " + file, e);
            }
        }
    }

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        MAIN.load();
        CONN.load();
    }

    /** Ghi một sự kiện. Gọi được từ mọi luồng; ghi xuống file trên luồng IO. */
    static void add(String event) {
        Log.d(WebViewHolder.TAG, "diag: " + event);
        synchronized (DiagLog.class) {
            if (app != null) MAIN.add(event);
        }
    }

    /** Sự kiện chỉ của nhật ký kết nối. */
    static void conn(String event) {
        synchronized (DiagLog.class) {
            if (app != null) CONN.add(event);
        }
    }

    /** Sự kiện hệ thống (app vào nền, màn hình, Doze, mạng...): ghi cả hai nhật ký. */
    static void sys(String event) {
        Log.d(WebViewHolder.TAG, "diag: " + event);
        synchronized (DiagLog.class) {
            if (app == null) return;
            MAIN.add(event);
            CONN.add(event);
        }
    }

    /**
     * Tình trạng hạn chế chạy nền lúc này: màn hình, tiết kiệm pin, Doze, bỏ qua tối ưu pin, nhóm chờ (standby bucket:
     * 5 exempted, 10 active, 20 working set, 30 frequent, 40 rare, 45 restricted), hạn chế nền, tiết kiệm dữ liệu
     * (1 tắt, 2 app được miễn, 3 bật), mạng. Ghi kèm khi app vào nền và mỗi lần hẹn giờ kiểm tra kết nối.
     */
    static String powerState(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            if (pm != null) {
                sb.append("screen=").append(pm.isInteractive() ? "on" : "off")
                        .append(" saver=").append(pm.isPowerSaveMode())
                        .append(" doze=").append(pm.isDeviceIdleMode())
                        .append(" batteryUnrestricted=").append(pm.isIgnoringBatteryOptimizations(c.getPackageName()));
            }
            if (Build.VERSION.SDK_INT >= 28) {
                android.app.usage.UsageStatsManager us = c.getSystemService(android.app.usage.UsageStatsManager.class);
                if (us != null) sb.append(" bucket=").append(us.getAppStandbyBucket());
                ActivityManager am = c.getSystemService(ActivityManager.class);
                if (am != null) sb.append(" bgRestricted=").append(am.isBackgroundRestricted());
            }
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            if (cm != null) {
                sb.append(" dataSaver=").append(cm.getRestrictBackgroundStatus());
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                sb.append(" net=").append(nc == null ? "none"
                        : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                        : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell" : "other");
            }
        } catch (RuntimeException e) {
            sb.append(" err=").append(e.getClass().getSimpleName());
        }
        return sb.toString().trim();
    }

    /** Toàn bộ nhật ký kèm thông tin máy, để dán gửi cho người sửa lỗi. */
    static synchronized String dump(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("ZaloDuo ").append(versionName(c))
                .append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" | WebView ").append(webViewVersion()).append('\n');
        for (String s : MAIN.lines) sb.append(s).append('\n');
        appendExitReasons(c, sb);
        return sb.toString();
    }

    /** Nhật ký kết nối kèm thông tin máy và tình trạng chạy nền lúc chép, để dán gửi cho người sửa lỗi. */
    static synchronized String dumpConn(Context c) {
        StringBuilder sb = new StringBuilder("=== ZaloDuo connection log ===\n");
        sb.append("ZaloDuo ").append(versionName(c))
                .append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" | WebView ").append(webViewVersion()).append('\n');
        sb.append("now: ").append(powerState(c)).append(" keepAlive=").append(Prefs.keepAlive(c)).append('\n');
        for (String s : CONN.lines) sb.append(s).append('\n');
        appendExitReasons(c, sb);
        return sb.toString();
    }

    /** Các lần tiến trình app / WebView bị tắt gần đây và lý do (hệ thống, hết bộ nhớ, quản lý pin, cập nhật app...). */
    private static void appendExitReasons(Context c, StringBuilder sb) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 15);
            if (list.isEmpty()) return;
            sb.append("--- process exits (newest first)\n");
            synchronized (TIME) {
                for (ApplicationExitInfo e : list) {
                    sb.append(TIME.format(new Date(e.getTimestamp()))).append("  ")
                            .append(e.getProcessName().contains("sandboxed") ? "webview renderer" : "app")
                            .append(" reason=").append(e.getReason())
                            .append(" importance=").append(e.getImportance())
                            .append(" ").append(e.getDescription()).append('\n');
                }
            }
        } catch (RuntimeException e) {
            sb.append("--- process exits unavailable: ").append(e).append('\n');
        }
    }

    static synchronized void clear() {
        if (app == null) return;
        MAIN.lines.clear();
        MAIN.scheduleSave();
    }

    static synchronized void clearConn() {
        if (app == null) return;
        CONN.lines.clear();
        CONN.scheduleSave();
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String webViewVersion() {
        try {
            PackageInfo p = WebView.getCurrentWebViewPackage();
            return p == null ? "?" : p.packageName + " " + p.versionName;
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
