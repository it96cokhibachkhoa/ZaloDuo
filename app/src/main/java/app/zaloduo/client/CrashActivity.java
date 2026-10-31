package app.zaloduo.client;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Hiện log của lần văng app gần nhất để người dùng sao chép / chia sẻ cho người sửa lỗi. */
public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String log = CrashLog.read(this);
        CrashLog.clear(this); // chỉ hiện một lần; lần mở sau vào thẳng Zalo
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ZaloDuo vừa bị văng (force close)");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Bấm \"Sao chép\" rồi dán vào cuộc trò chuyện để sửa lỗi. "
                + "Log chỉ hiện một lần và chỉ lưu trên máy này.");
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Sao chép", () -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("ZaloDuo crash", log));
            Toast.makeText(this, "Đã sao chép log lỗi", Toast.LENGTH_SHORT).show();
        }), weight());
        buttons.addView(button("Chia sẻ", () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log);
            try {
                startActivity(Intent.createChooser(send, "Gửi log lỗi"));
            } catch (ActivityNotFoundException ignored) {
            }
        }), weight());
        buttons.addView(button("Mở lại app", () -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }), weight());
        root.addView(buttons);

        TextView body = new TextView(this);
        body.setText(log);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(12), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
