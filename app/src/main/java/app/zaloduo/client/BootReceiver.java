package app.zaloduo.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bật lại dịch vụ chạy nền sau khi khởi động máy hoặc cập nhật app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            DiagLog.sys(Intent.ACTION_BOOT_COMPLETED.equals(action) ? "boot completed" : "app updated");
            if (Prefs.keepAlive(context) && Prefs.startOnBoot(context)) KeepAliveService.start(context);
        }
    }
}
