package app.zaloduo.client;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bộ nhớ ảnh / video của Zalo trong dữ liệu riêng của app (files/media), như Zalo mobile: mỗi file tải một lần, sau đó
 * trang (WebView, qua shouldInterceptRequest), trình xem... đọc thẳng từ máy — mở lại hội thoại cũ, xem lại ảnh / video
 * quá khứ là có ngay, mạng yếu cũng được. Khi tin nhắn được nạp, shim.js nhờ tải trước ảnh HD + video (prefetch).
 * Video phát theo đoạn (yêu cầu Range) cũng trả được từ file đã lưu. Giới hạn dung lượng: đầy thì xoá file lâu không
 * dùng nhất.
 */
final class MediaCache {
    private static final long LIMIT = 1024L * 1024 * 1024; // 1 GB
    private static final ExecutorService PREFETCH = Executors.newFixedThreadPool(2);
    private static final Set<String> LOADING = Collections.synchronizedSet(new HashSet<>());
    private static volatile long total = -1; // tổng dung lượng (tính một lần, cộng dồn khi ghi)

    private MediaCache() {
    }

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "media");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Ảnh / video tin nhắn, ảnh đại diện... của Zalo (không gồm mã / giao diện tĩnh của trang). */
    static boolean cacheable(Uri u) {
        String h = u.getHost();
        if (h == null || !"https".equals(u.getScheme())) return false;
        h = h.toLowerCase(Locale.ROOT);
        if (!(h.endsWith(".zdn.vn") || h.endsWith(".zadn.vn") || h.endsWith(".dlmd.me"))) return false;
        if (h.contains("static") || h.startsWith("chat.") || h.startsWith("wpa.") || h.startsWith("api")) return false;
        String p = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
        return h.startsWith("photo") || h.startsWith("video") || h.contains("-ava") || h.contains("msg-photo")
                || h.contains("cover") || p.matches(".*\\.(jpe?g|png|webp|gif|jxl|mp4|mov|m4a|aac)$");
    }

    private static String key(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b));
            return s.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    // ------------------------------------------------------------------ WebView

    /** Trả từ máy nếu đã có; ảnh chưa có thì tải ngay (vừa đưa cho trang vừa ghi xuống); video chưa có: tải nền. */
    static WebResourceResponse intercept(Context c, WebResourceRequest req) {
        try {
            if (!"GET".equals(req.getMethod()) || !cacheable(req.getUrl())) return null;
            String url = req.getUrl().toString();
            File dir = dir(c), f = new File(dir, key(url)), t = new File(dir, key(url) + ".t");
            String range = header(req.getRequestHeaders(), "Range");
            if (f.exists() && t.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis()); // vừa dùng: xoá sau cùng khi đầy
                return fromFile(f, new String(Files.readAllBytes(t.toPath()), StandardCharsets.UTF_8), range);
            }
            if (range != null || url.contains("video")) { // video phát theo đoạn: để trang tự tải, mình tải đủ file ở nền
                prefetch(c, Collections.singletonList(url));
                return null;
            }
            return fetchTee(c, url, f, t);
        } catch (Exception e) { // lỗi đọc / mạng: để trang tự tải như bình thường
            return null;
        }
    }

    private static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        return null;
    }

    private static WebResourceResponse fromFile(File f, String mime, String range) throws IOException {
        long len = f.length();
        Map<String, String> hd = new HashMap<>();
        hd.put("Access-Control-Allow-Origin", "*");
        hd.put("Accept-Ranges", "bytes");
        hd.put("Cache-Control", "max-age=31536000");
        if (range != null && range.startsWith("bytes=")) {
            String[] p = range.substring(6).split("-", 2);
            long start = p[0].isEmpty() ? 0 : Long.parseLong(p[0].trim());
            long end = p.length > 1 && !p[1].trim().isEmpty() ? Math.min(Long.parseLong(p[1].trim()), len - 1) : len - 1;
            if (start >= len) start = Math.max(0, len - 1);
            InputStream in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) {
                long s = in.skip(start - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            hd.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            hd.put("Content-Length", String.valueOf(end - start + 1));
            return new WebResourceResponse(mime, null, 206, "Partial Content", hd, new Limited(in, end - start + 1));
        }
        hd.put("Content-Length", String.valueOf(len));
        return new WebResourceResponse(mime, null, 200, "OK", hd, new FileInputStream(f));
    }

    /** Tải ảnh: luồng đưa cho trang đồng thời ghi xuống file tạm; đọc hết đủ mới thành file thật. */
    private static WebResourceResponse fetchTee(Context c, String url, File f, File t) throws IOException {
        HttpURLConnection con = open(url);
        int code = con.getResponseCode();
        String type = con.getContentType();
        if (code != 200 || type == null || !(type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))) {
            con.disconnect();
            return null;
        }
        String mime = type.split(";")[0].trim();
        long expect = con.getContentLengthLong();
        File part = new File(f.getPath() + ".part" + Thread.currentThread().getId());
        InputStream tee = new Tee(con.getInputStream(), new FileOutputStream(part), () -> {
            if (expect < 0 || part.length() == expect) commit(c, part, f, t, mime);
            //noinspection ResultOfMethodCallIgnored
            else part.delete();
            con.disconnect();
        });
        Map<String, String> hd = new HashMap<>();
        hd.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(mime, null, 200, "OK", hd, tee);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        return con;
    }

    private static synchronized void commit(Context c, File part, File f, File t, String mime) {
        try {
            try (OutputStream o = new FileOutputStream(t)) {
                o.write(mime.getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return;
            }
            if (total >= 0) total += f.length();
            trim(c);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    // ------------------------------------------------------------------ tải trước

    /** Tải nền các file chưa có (shim.js gửi khi nạp tin: ảnh HD, video...). */
    static void prefetch(Context c, List<String> urls) {
        final Context app = c.getApplicationContext();
        for (final String url : urls) {
            if (url == null || !cacheable(Uri.parse(url))) continue;
            final File dir = dir(app), f = new File(dir, key(url)), t = new File(dir, key(url) + ".t");
            if (f.exists() || !LOADING.add(url)) continue;
            PREFETCH.execute(() -> fetchOne(app, url, f, t, 1));
        }
    }

    /**
     * Tải hẳn một link xuống bộ nhớ. Đứt giữa chừng (mạng chập chờn, lỗi SSL, thiếu byte): thử lại sau 2 s, 4 s — nếu
     * không, video chỉ được tải lại khi mở trình xem (trang chỉ gửi mỗi link một lần).
     */
    private static void fetchOne(Context app, String url, File f, File t, int attempt) {
        File part = new File(f.getPath() + ".pre");
        boolean done = false, permanent = false;
        try {
            HttpURLConnection con = open(url);
            String type = con.getContentType();
            int code = con.getResponseCode();
            if (code == 200 && type != null) {
                long expect = con.getContentLengthLong();
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (expect < 0 || part.length() == expect) {
                    commit(app, part, f, t, type.split(";")[0].trim());
                    done = true;
                }
            } else permanent = code >= 400 && code < 500; // link hết hạn / không có: thử lại vô ích
            con.disconnect();
        } catch (Exception ignored) { // đứt giữa chừng: thử lại bên dưới
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        if (done || permanent || attempt >= 3 || f.exists()) {
            LOADING.remove(url);
            return;
        }
        App.MAIN.postDelayed(() -> PREFETCH.execute(() -> fetchOne(app, url, f, t, attempt + 1)), 2000L * attempt);
    }

    // ------------------------------------------------------------------ dung lượng

    static synchronized long size(Context c) {
        long s = 0;
        File[] l = dir(c).listFiles();
        if (l != null) for (File x : l) s += x.length();
        total = s;
        return s;
    }

    static synchronized void clear(Context c) {
        File[] l = dir(c).listFiles();
        if (l != null) for (File x : l) //noinspection ResultOfMethodCallIgnored
            x.delete();
        total = 0;
    }

    /** Quá giới hạn: xoá file lâu không dùng nhất tới khi còn 90%. */
    private static void trim(Context c) {
        if (total < 0) size(c);
        if (total <= LIMIT) return;
        File[] l = dir(c).listFiles(x -> !x.getName().contains("."));
        if (l == null) return;
        Arrays.sort(l, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File x : l) {
            if (total <= LIMIT * 9 / 10) break;
            total -= x.length();
            //noinspection ResultOfMethodCallIgnored
            x.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(x.getPath() + ".t").delete();
        }
    }

    // ------------------------------------------------------------------ luồng

    /** Đọc từ mạng, ghi kèm xuống file; đóng luồng thì gọi done (kiểm tra đủ byte rồi lưu). */
    private static final class Tee extends FilterInputStream {
        private final OutputStream out;
        private final Runnable done;
        private boolean closed, broken;

        Tee(InputStream in, OutputStream out, Runnable done) {
            super(in);
            this.out = out;
            this.done = done;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && !broken) try { out.write(b); } catch (IOException e) { broken = true; }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && !broken) try { out.write(b, off, n); } catch (IOException e) { broken = true; }
            return n;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                // Trang thôi đọc giữa chừng: đọc nốt cho đủ file (ảnh nhỏ) để lần sau có sẵn.
                byte[] buf = new byte[16 * 1024];
                while (!broken && read(buf, 0, buf.length) > 0) {
                    // read() ở trên vừa đưa vừa ghi xuống file
                }
            } catch (IOException ignored) {
            }
            try { out.close(); } catch (IOException ignored) { }
            try { super.close(); } catch (IOException ignored) { }
            done.run();
        }
    }

    /** Chỉ cho đọc n byte (trả một đoạn file cho yêu cầu Range). */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long n) {
            super(in);
            left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
