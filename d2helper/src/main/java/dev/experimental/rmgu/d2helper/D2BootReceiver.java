package dev.experimental.rmgu.d2helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class D2BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try {
            if (!D2Store.enabled(context)) {
                EarlyRootBoot.request(context, action);
                return;
            }
            PendingResult pending = goAsync();
            try { D2Runtime.request(context, action, true, () -> {
                try { EarlyRootBoot.request(context, action); }
                finally { pending.finish(); }
            }); }
            catch (Exception error) { pending.finish(); throw error; }
        } catch (Exception error) {
            Log.w("RMGUD2", "Boot request failed: " + error.getClass().getSimpleName());
        }
    }
}
