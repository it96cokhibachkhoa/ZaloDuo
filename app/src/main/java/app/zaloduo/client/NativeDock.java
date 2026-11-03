package app.zaloduo.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dock ở đáy (Danh bạ – Tin nhắn – My Documents) vẽ bằng app, nằm trên WebView trong webContainer (giao diện native).
 * Trạng thái (biểu tượng, số chưa đọc, nút đang chọn, hiện / trượt ẩn, màu) do shim.js gửi (mục 8, JsBridge.dockState);
 * chạm nút thì app bấm hộ nút tương ứng của dock trang (đã ẩn, chỉ còn lớp làm nhòe nền phía sau). Mở app dùng trạng
 * thái lần trước nên dock có ngay, không chờ trang tải.
 *
 * Kính mờ (lớp nhòe danh sách native phía sau, NativeChatList.dockGlass) là view con của dock: vẽ theo thứ tự bóng đổ →
 * kính → lớp màu, viên thuốc, biểu tượng — ẩn / hiện, trượt cùng một lớp, không lệch nhịp như hai lớp riêng.
 */
final class NativeDock extends FrameLayout {
    private static final String SAVED = "native_dock_state";
    /** Cao dock theo px CSS của trang (= dp), như #main-tab của shim.js; M: lề quanh dock để vẽ bóng đổ; tối đa 5 nút. */
    private static final int H = 56, M = 24, MAX = 5;
    /** Bề rộng dock (px CSS, theo số nút — shim.js dockWidth) và số nút: Danh bạ, Tin nhắn, My Documents, [zCloud], [Công cụ]. */
    private int W = 228, n = 3;
    private static final PathInterpolator EASE = new PathInterpolator(.22f, .61f, .36f, 1f); // = transition của #main-tab
    private static final PathInterpolator PILL_EASE = new PathInterpolator(.2f, .8f, .2f, 1f); // = #zd-ind

    private final float density, zoom;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG), badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), tmp = new RectF();
    private final Path boxPath = new Path(), topLine = new Path();

    private final String[] icons = {"Contact_28_Line", "Message_28_Filled", "MyDocs_24_Line", "", ""};
    private final String[] keys = {"STR_TAB_CONTACT", "STR_TAB_MESSAGE", "KEY_NAME_SEND2ME", "", ""};
    private final String[] badges = {"", "", "", "", ""};
    private final float[] sizes = {28, 28, 28, 28, 28};
    private final boolean[] present = {true, true, true, false, false};
    private int sel = 1;
    private float pillX = -1;
    private android.animation.ValueAnimator pillAnim;
    private boolean visible = true, slid;
    private android.animation.ObjectAnimator fade, slide;
    private String last;
    /** Kính mờ (view con) và màu nền dock trang gửi (rgb; độ đậm lấy từ Cài đặt — applyOpacity). */
    private View glass;
    private int baseTint = 0xFF005AE0;

    NativeDock(Context c) {
        super(c);
        setWillNotDraw(false);
        setClipChildren(false);
        density = getResources().getDisplayMetrics().density;
        zoom = Prefs.textZoom(c) / 100f;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        glyph.setTypeface(ZaloIcons.font(c));
        glyph.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(Typeface.DEFAULT_BOLD);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setColor(Color.WHITE);
        badgeText.setTextSize(10 * density * zoom);
        badgeBg.setColor(0xFFE5383B); // = badge chưa đọc ở danh sách native
        shadow.setColor(Color.TRANSPARENT);
        shadow.setShadowLayer(20 * density / 2, 0, 6 * density, 0x40000000); // box-shadow 0 6px 20px rgba(0,0,0,.25)
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF); // inset 0 0 0 1px rgba(255,255,255,.12)
        // Màu mặc định (chưa có trạng thái từ trang): dock xanh của Zalo, biểu tượng trắng.
        applyOpacity();
        glyph.setColor(Color.WHITE);
        pill.setColor(0x29000000);
        setTranslationZ(1001); // trên WebView và trên danh sách native lúc mở app (boot, Z 1000)
        apply(Prefs.sp(c).getString(SAVED, null), false);
    }

    /** Gắn lớp kính mờ (NativeChatList.dockGlass) vào trong dock, đúng khung dock. */
    void setGlass(View g) {
        if (g == null) return;
        if (g.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) g.getParent()).removeView(g);
        glass = g;
        addView(g, 0, glassParams());
    }

    private FrameLayout.LayoutParams glassParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(W), dp(H), Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(M);
        lp.topMargin = dp(M);
        return lp;
    }

    /** Độ đậm nền dock theo Cài đặt (0-100%), áp ngay — không chờ trang tính lại màu. */
    void applyOpacity() {
        int a = Math.round(Math.max(0, Math.min(100, Prefs.dockOpacity(getContext()))) * 2.55f);
        tint.setColor((baseTint & 0x00FFFFFF) | (a << 24));
        invalidate();
    }

    /** Khung của view: dock + lề M quanh để vẽ bóng, sát đáy cách theo Cài đặt. */
    FrameLayout.LayoutParams params() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(W + 2 * M), dp(H + 2 * M),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(Prefs.dockBottom(getContext()) - M);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Trạng thái dock từ trang (JsBridge.dockState). */
    void setState(String json) {
        apply(json, true);
    }

    private void apply(String json, boolean live) {
        if (json == null || json.equals(last)) return;
        last = json;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray t = o.optJSONArray("t");
            int newSel = sel;
            if (t != null) {
                n = Math.max(1, Math.min(MAX, t.length()));
                for (int i = 0; i < n; i++) {
                    JSONArray it = t.optJSONArray(i);
                    present[i] = it != null;
                    if (it == null) continue;
                    if (!it.optString(0).isEmpty()) icons[i] = it.optString(0);
                    badges[i] = it.optString(1);
                    if (it.optInt(2) == 1) newSel = i;
                    sizes[i] = (float) it.optDouble(3, 28);
                    keys[i] = it.optString(4, keys[i]);
                }
                if (sel >= n) sel = Math.min(1, n - 1);
            }
            int w = o.optInt("w", W);
            if (w != W) { // bật / tắt nút zCloud, Công cụ: dock rộng / hẹp lại
                W = w;
                if (getParent() != null) setLayoutParams(params());
                if (glass != null) glass.setLayoutParams(glassParams());
            }
            int c = parse(o.optString("c"));
            if (c != 0) { // chỉ lấy màu (sáng / tối theo giao diện Zalo); độ đậm theo Cài đặt
                baseTint = c;
                applyOpacity();
            }
            int ic = parse(o.optString("ic"));
            if (ic != 0) glyph.setColor(ic);
            int ind = parse(o.optString("ind"));
            if (ind != 0) pill.setColor(ind);
            if (newSel != sel) moveTo(newSel, live && pillX >= 0);
            stateV = o.optBoolean("v");
            setShown(stateV, o.optBoolean("dh"), live);
            if (live) Prefs.sp(getContext()).edit().putString(SAVED, json).apply();
            invalidate();
        } catch (Exception e) {
            Log.w(WebViewHolder.TAG, "bad dock state", e);
        }
    }

    /** Màu CSS rgb() / rgba() → ARGB; 0 nếu không đọc được. */
    private static int parse(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("rgba?\\(\\s*([\\d.]+),\\s*([\\d.]+),\\s*([\\d.]+)(?:,\\s*([\\d.]+))?").matcher(s == null ? "" : s);
        if (!m.find()) return 0;
        float a = m.group(4) == null ? 1f : Float.parseFloat(m.group(4));
        return Color.argb(Math.round(a * 255), Math.round(Float.parseFloat(m.group(1))),
                Math.round(Float.parseFloat(m.group(2))), Math.round(Float.parseFloat(m.group(3))));
    }

    /** Ẩn hẳn (đang mở chat, hộp thoại che): mờ đi. Trượt ẩn (cuộn xuống, đang tìm kiếm): trượt xuống như dock web. */
    private void setShown(boolean v, boolean dh, boolean animate) {
        // Mờ và trượt là hai hiệu ứng riêng (ViewPropertyAnimator gộp chung: hủy cái này dừng luôn cái kia giữa chừng).
        if (v != visible) {
            visible = v;
            if (fade != null) fade.cancel();
            if (v) setVisibility(VISIBLE);
            if (animate) {
                fade = android.animation.ObjectAnimator.ofFloat(this, ALPHA, v ? 1f : 0f);
                fade.setDuration(v ? 200 : 120);
                fade.setInterpolator(EASE);
                fade.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator a) {
                        if (!visible) setVisibility(GONE);
                    }
                });
                fade.start();
            } else {
                setAlpha(v ? 1f : 0f);
                setVisibility(v ? VISIBLE : GONE);
            }
        }
        if (dh != slid) {
            slid = dh;
            float y = dh ? dp(H + Prefs.dockBottom(getContext()) + 12) : 0;
            if (slide != null) slide.cancel();
            if (animate) {
                slide = android.animation.ObjectAnimator.ofFloat(this, TRANSLATION_Y, y);
                slide.setDuration(420);
                slide.setInterpolator(EASE);
                // Dock trượt thì phần danh sách nằm sau kính đổi theo: vẽ lại kính từng khung hình.
                slide.addUpdateListener(an -> { if (glass != null) glass.invalidate(); });
                slide.start();
            } else setTranslationY(y);
            if (glass != null) glass.invalidate();
        }
    }

    /**
     * Vừa chạm mở hội thoại ở danh sách native: mờ dock ngay (trang báo "đang mở chat" trễ một nhịp, dock còn đè lên
     * khung chat đang trượt vào). Zalo không mở được (trang vẫn báo dock hiện) thì sau 1,5 s hiện lại.
     */
    void hideForOpen() {
        setShown(false, slid, true);
        removeCallbacks(restore);
        postDelayed(restore, 1500);
    }

    /** Trạng thái "hiện" trang báo lần cuối. */
    private boolean stateV = true;
    private final Runnable restore = () -> { if (stateV) setShown(true, slid, true); };

    /** Trang cần người dùng (màn đăng nhập): ẩn ngay. */
    void hideNow() {
        setShown(false, slid, false);
    }

    // ------------------------------------------------------------------ vẽ

    private float btnW() {
        return (dp(W) - dp(4) - n * dp(4)) / (float) n; // padding 0 2px, mỗi nút margin 4px 2px (shim.js)
    }

    private float btnLeft(int i) {
        return box.left + dp(2) + dp(2) + i * (btnW() + dp(4));
    }

    private void moveTo(int i, boolean animate) {
        sel = i;
        float to = btnLeft(i);
        if (pillAnim != null) pillAnim.cancel();
        if (!animate || box.isEmpty()) {
            pillX = box.isEmpty() ? -1 : to;
            invalidate();
            return;
        }
        pillAnim = android.animation.ValueAnimator.ofFloat(pillX, to);
        pillAnim.setDuration(350);
        pillAnim.setInterpolator(PILL_EASE);
        pillAnim.addUpdateListener(a -> {
            pillX = (float) a.getAnimatedValue();
            invalidate();
        });
        pillAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        box.set(dp(M), dp(M), dp(M) + dp(W), dp(M) + dp(H));
        float r = box.height() / 2f;
        boxPath.reset();
        boxPath.addRoundRect(box, r, r, Path.Direction.CW);
        // Vệt sáng mảnh ở mép trên trong (inset 0 1px 0 rgba(255,255,255,.22)): dock trừ đi chính nó dịch xuống 1px.
        Path shifted = new Path();
        tmp.set(box);
        tmp.offset(0, density);
        shifted.addRoundRect(tmp, r, r, Path.Direction.CW);
        topLine.reset();
        topLine.op(boxPath, shifted, Path.Op.DIFFERENCE);
        pillX = btnLeft(sel);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        float r = box.height() / 2f;
        // Bóng đổ chỉ bên ngoài dock (nền dock trong suốt một phần, không để bóng lộ qua).
        c.save();
        c.clipOutPath(boxPath);
        c.drawRoundRect(box, r, r, shadow);
        c.restore();
        c.save();
        c.clipPath(boxPath);
        super.dispatchDraw(c); // kính mờ (view con) nằm dưới lớp màu
        c.drawRoundRect(box, r, r, tint);
        if (pillX >= 0 && present[sel]) {
            tmp.set(pillX, box.top + dp(4), pillX + btnW(), box.top + dp(4) + dp(48));
            c.drawRoundRect(tmp, dp(24), dp(24), pill);
        }
        Paint.FontMetrics fm = new Paint.FontMetrics();
        for (int i = 0; i < n; i++) {
            if (!present[i]) continue;
            float cx = btnLeft(i) + btnW() / 2f, cy = box.centerY();
            glyph.setTextSize(sizes[i] * density * zoom);
            glyph.getFontMetrics(fm);
            c.drawText(ZaloIcons.glyph(getContext(), icons[i]), cx, cy - (fm.ascent + fm.descent) / 2f, glyph);
            drawBadge(c, badges[i], cx + sizes[i] * density * zoom / 2f, cy - sizes[i] * density * zoom / 2f);
        }
        c.drawPath(topLine, edgeTop());
        c.restore();
        c.drawRoundRect(box.left + density / 2, box.top + density / 2, box.right - density / 2, box.bottom - density / 2,
                r, r, edge);
    }

    private Paint topPaint;

    private Paint edgeTop() {
        if (topPaint == null) {
            topPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            topPaint.setColor(0x38FFFFFF);
        }
        return topPaint;
    }

    /** Số chưa đọc ở góc trên phải biểu tượng (như web): "." là chấm đỏ không số. */
    private void drawBadge(Canvas c, String b, float x, float y) {
        if (b == null || b.isEmpty()) return;
        if (b.equals(".")) {
            c.drawCircle(x - dp(3), y + dp(3), dp(4), badgeBg);
            return;
        }
        float h = dp(16) * zoom, w = Math.max(h, badgeText.measureText(b) + dp(8));
        tmp.set(x - w / 2f, y - h / 2f + dp(2), x + w / 2f, y + h / 2f + dp(2));
        c.drawRoundRect(tmp, h / 2f, h / 2f, badgeBg);
        Paint.FontMetrics fm = badgeText.getFontMetrics();
        c.drawText(b, tmp.centerX(), tmp.centerY() - (fm.ascent + fm.descent) / 2f, badgeText);
    }

    // ------------------------------------------------------------------ chạm

    private int down = -1;

    private int hit(float x, float y) {
        if (!box.contains(x, y)) return -1;
        for (int i = 0; i < n; i++) {
            if (present[i] && x >= btnLeft(i) - dp(2) && x < btnLeft(i) + btnW() + dp(2)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!visible || slid || !box.contains(e.getX(), e.getY())) return false; // lề bóng đổ: chạm xuyên xuống
                down = hit(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
                int i = hit(e.getX(), e.getY());
                if (i >= 0 && i == down) tap(i);
                down = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                down = -1;
                return true;
            default:
                return true;
        }
    }

    private void tap(int i) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        // Viên thuốc trượt ngay (chỉ Danh bạ / Tin nhắn — là tab thật), trang báo lại nút đang chọn sau. My Documents mở hội
        // thoại, zCloud / Công cụ mở menu hay trang riêng: tab Tin nhắn vẫn chọn, không trượt.
        if (i != sel && ("STR_TAB_CONTACT".equals(keys[i]) || "STR_TAB_MESSAGE".equals(keys[i]))) moveTo(i, true);
        last = null;
        WebViewHolder.evalMain("window.__zw&&window.__zw.dockTap&&window.__zw.dockTap(" + i + ")");
    }
}
