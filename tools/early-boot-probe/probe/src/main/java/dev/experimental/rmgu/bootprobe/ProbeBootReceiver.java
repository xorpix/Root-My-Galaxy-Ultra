package dev.experimental.rmgu.bootprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProbeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String reason;
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction()))
            reason = "locked_boot_completed_received";
        else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
            reason = "boot_completed_received";
        else return;
        PendingResult pending = goAsync();
        ProbeRuntime.start(context, reason);
        ProbeRuntime.afterQueuedEvents(pending::finish);
    }
}
