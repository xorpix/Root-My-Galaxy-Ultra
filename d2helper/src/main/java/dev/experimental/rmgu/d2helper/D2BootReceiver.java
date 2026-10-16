package dev.experimental.rmgu.d2helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

public final class D2BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        long received = SystemClock.elapsedRealtime();
        try {
            if (!D2Store.enabled(context)) {
                EarlyRootBoot.request(context, action, received, "odin_disabled");
                return;
            }
            PendingResult pending = goAsync();
            AtomicBoolean finished = new AtomicBoolean();
            Runnable finish = () -> { if (finished.compareAndSet(false, true)) pending.finish(); };
            AtomicBoolean sent = new AtomicBoolean();
            java.util.function.Consumer<String> wake = reason -> {
                if (sent.compareAndSet(false, true) && !EarlyRootBoot.request(context, action, received, reason)) {
                    sent.set(false); // Retry at completion if the earlier platform start was refused.
                }
            };
            try { D2Runtime.request(context, action, true,
                () -> wake.accept("odin_verified"),
                () -> {
                    try { wake.accept("odin_complete"); }
                    finally { finish.run(); }
                }); }
            catch (Exception error) { finish.run(); throw error; }
        } catch (Exception error) {
            Log.w("RMGUD2", "Boot request failed: " + error.getClass().getSimpleName());
        }
    }
}
