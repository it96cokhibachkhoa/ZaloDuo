package app.zaloduo.client;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Chia sẻ tệp (ảnh đang xem) sang app khác qua bảng chia sẻ của Android. Tệp nằm tạm trong cache/share, đưa ra
 * ngoài qua ContentProvider chỉ đọc này (chỉ app được cấp quyền theo từng lần chia sẻ mới mở được).
 */
public class ShareFiles extends ContentProvider {
    static final String AUTHORITY = "app.zaloduo.client.share";

    static File dir(Context c) {
        return new File(c.getCacheDir(), "share");
    }

    /** Ghi tệp vào cache/share (xóa tệp chia sẻ cũ) rồi mở bảng chia sẻ. Gọi trên luồng chính. */
    static void share(Context c, String name, String mime, String base64) {
        File d = dir(c);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        String safe = new File(name).getName().replaceAll("[^A-Za-z0-9._-]", "_");
        File out = new File(d, safe.isEmpty() ? "zalo-file" : safe);
        try (FileOutputStream os = new FileOutputStream(out)) {
            os.write(Base64.decode(base64, Base64.DEFAULT));
        } catch (IOException | IllegalArgumentException e) {
            Log.w(WebViewHolder.TAG, "share: write failed", e);
            return;
        }
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(out.getName()));
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(out.getName(), uri));
        Intent chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(chooser);
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "share: no chooser", e);
        }
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || getContext() == null) throw new FileNotFoundException();
        File f = new File(dir(getContext()), new File(name).getName());
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(String.valueOf(uri.getLastPathSegment()));
        String t = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        return t != null ? t : "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
