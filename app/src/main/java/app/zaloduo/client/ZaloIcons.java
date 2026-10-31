package app.zaloduo.client;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Biểu tượng của Zalo Web, gói trong app: font biểu tượng "zalo-font" của Zalo (res/font/zalo_icons.ttf) và bảng tên
 * → mã ký tự lấy từ CSS của Zalo (assets/zalo_icons.json, tên như class "fa-…" của trang, bỏ "fa-"). Dùng cho phần
 * app tự vẽ (thanh trên tạm lúc mở app...) để biểu tượng giống hệt trang.
 */
final class ZaloIcons {
    private static Typeface font;
    private static JSONObject codes;

    private ZaloIcons() {
    }

    static synchronized Typeface font(Context c) {
        if (font == null) {
            try {
                font = c.getResources().getFont(R.font.zalo_icons);
            } catch (RuntimeException e) {
                Log.w(WebViewHolder.TAG, "zalo icon font", e);
                font = Typeface.DEFAULT;
            }
        }
        return font;
    }

    /** Ký tự của biểu tượng theo tên (vd. "search", "solid-more"); "" nếu không có. */
    static synchronized String glyph(Context c, String name) {
        if (codes == null) {
            try (InputStream in = c.getAssets().open("zalo_icons.json")) {
                byte[] b = new byte[in.available()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                codes = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(WebViewHolder.TAG, "zalo icon map", e);
                codes = new JSONObject();
            }
        }
        String hex = codes.optString(name);
        return hex.isEmpty() ? "" : new String(Character.toChars(Integer.parseInt(hex, 16)));
    }

    /** Ô chữ hiện một biểu tượng, cỡ theo px CSS của trang (font-size của biểu tượng trên web). */
    static TextView view(Context c, String name, float sizePx, int color) {
        TextView t = new TextView(c);
        t.setTypeface(font(c));
        t.setText(glyph(c, name));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizePx);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }
}
