package app.zaloduo.client;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.webkit.WebView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ghi lại lỗi làm app văng (force close) vào tệp, để lần mở sau hiện ra cho người dùng sao chép.
 * Không gửi đi đâu cả.
 */
final class CrashLog {
    private static final String FILE = "last_crash.txt";

    private CrashLog() {}

    static void install(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(app, thread, error);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    private static void save(Context c, Thread thread, Throwable error) throws IOException {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("ZaloDuo " + versionName(c));
        pw.println("Thời điểm: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("Máy: " + Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("WebView: " + webViewVersion());
        pw.println("Luồng: " + thread.getName());
        pw.println();
        error.printStackTrace(pw);
        pw.flush();
        try (OutputStream os = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
            os.write(sw.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Có log văng app chưa xem không. Log ghi trước lần cài/cập nhật app gần nhất là của bản cũ
     * (đã được sửa), nên bị xóa luôn thay vì hiện ra.
     */
    static boolean hasPending(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return false;
        try {
            long updated = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            if (f.lastModified() < updated) {
                clear(c);
                return false;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Không đọc được log lỗi: " + e;
        }
    }

    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), FILE).delete();
    }

    private static String versionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String webViewVersion() {
        try {
            PackageInfo pi = WebView.getCurrentWebViewPackage();
            return pi == null ? "không rõ" : pi.packageName + " " + pi.versionName;
        } catch (Throwable t) {
            return "không rõ (" + t.getClass().getSimpleName() + ")";
        }
    }
}
