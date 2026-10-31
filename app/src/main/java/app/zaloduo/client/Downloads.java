package app.zaloduo.client;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Tải tệp từ Zalo Web vào thư mục Download/Zalo. */
final class Downloads {
    private Downloads() {}

    static final class Listener implements DownloadListener {
        private final Context app;
        private final WebView owner;

        Listener(Context app, WebView owner) {
            this.app = app.getApplicationContext();
            this.owner = owner;
        }

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // DownloadManager không đọc được blob: — để trang tự đọc rồi chuyển dữ liệu qua bridge.
                owner.evaluateJavascript("window.__zw&&window.__zw.saveUrl(" + JSONObject.quote(url) + ","
                        + JSONObject.quote(name) + ")", null);
                return;
            }
            if (url.startsWith("https://") || url.startsWith("http://")) {
                enqueue(app, url, userAgent, name, mimeType);
            }
        }
    }

    static void enqueue(Context c, String url, String ua, String name, String mime) {
        if (Build.VERSION.SDK_INT < 29 && !hasLegacyStoragePermission(c)) {
            askStoragePermission(c);
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.addRequestHeader("User-Agent", ua != null ? ua : WebViewHolder.userAgent);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Zalo/" + sanitize(name));
            c.getSystemService(DownloadManager.class).enqueue(r);
            WebViewHolder.toast(c.getString(R.string.download_started, name));
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "enqueue download", e);
            WebViewHolder.toast(c.getString(R.string.download_failed));
        }
    }

    /** Gọi từ luồng JS bridge với nội dung tệp dạng base64. */
    static void saveBase64(Context c, String name, String mime, String base64) {
        String fileName = sanitize(name);
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, type);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Zalo");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("MediaStore insert failed");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IOException("openOutputStream failed");
                    os.write(data);
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            } else {
                if (!hasLegacyStoragePermission(c)) {
                    askStoragePermission(c);
                    return;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Zalo");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs failed");
                File f = unique(dir, fileName);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{type}, null);
                fileName = f.getName();
            }
            WebViewHolder.toast(c.getString(R.string.download_saved, fileName));
        } catch (Exception e) {
            Log.w(WebViewHolder.TAG, "saveBase64", e);
            WebViewHolder.toast(c.getString(R.string.download_failed));
        }
    }

    private static boolean hasLegacyStoragePermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static void askStoragePermission(Context c) {
        WebViewHolder.toast(c.getString(R.string.download_need_permission));
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.requestStoragePermission();
        });
    }

    static String sanitize(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "zalo-file";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}
