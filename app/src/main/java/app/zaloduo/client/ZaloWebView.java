package app.zaloduo.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;

/** WebView kèm vài trạng thái riêng: URL hiện tại (đọc được từ luồng JS bridge), script đã chèn, là popup hay không. */
@SuppressLint("ViewConstructor")
final class ZaloWebView extends WebView {
    final boolean popup;
    volatile String lastUrl = "";
    ScriptHandler scriptHandler;
    boolean shown;

    /**
     * Danh sách chat native nằm dưới WebView (NativeChatList): chạm bắt đầu từ passTop trở xuống (px của view), trừ vùng
     * dock passExclude [l,t,r,b], thì WebView không nhận để chạm rơi xuống danh sách. passTop &lt; 0: tắt.
     */
    volatile int passTop = -1;
    volatile int[] passExclude;

    ZaloWebView(Context context, boolean popup) {
        super(context);
        this.popup = popup;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && passTop >= 0 && ev.getY() >= passTop) {
            int[] d = passExclude;
            boolean inDock = d != null && ev.getX() >= d[0] && ev.getX() <= d[2] && ev.getY() >= d[1] && ev.getY() <= d[3];
            if (!inDock) return false; // cả cử chỉ này thuộc về danh sách native bên dưới
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * WebView đóng băng trang (sự kiện "freeze": dừng timer, ngừng nhận tin) khoảng 1 phút sau khi cửa sổ bị ẩn.
     * Khi bật chạy nền, luôn báo cửa sổ đang hiện để trang không bị đóng băng. Zalo vẫn biết app ở nền
     * qua document.hidden do shim.js giả lập (WebViewHolder.setHidden).
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(Prefs.keepAlive(getContext()) ? VISIBLE : visibility);
    }
}
