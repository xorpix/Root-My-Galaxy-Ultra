package dev.experimental.rmgu.d2helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.UserManager;
import dev.experimental.rmgu.d2.D2Configuration;
import dev.experimental.rmgu.d2.D2Protocol;
import java.io.IOException;

/** Fixed, signed app service only. No payload, root command or native library runs in system. */
final class EarlyRootBoot {
    private static final D2Configuration configuration = new D2Configuration(
            new D2SettingsFile(D2Protocol.EARLY_ROOT_SETTINGS_FILE));

    static boolean enabled() {
        try { return configuration.read().enabled; }
        catch (IOException error) { Log.w("RMGUEarlyRoot", "Boot policy unavailable; root not started", error); return false; }
    }

    static void configure(Context context, boolean enabled) throws IOException {
        if (enabled && !D2Store.snapshot(context).getBoolean("activated")) {
            throw new IOException("Activate the boot helper before enabling early root");
        }
        configuration.activate();
        configuration.setEnabled(enabled);
    }

    static boolean request(Context context, String action) {
        if (!(Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)) || !enabled()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.isSafeMode()) return false;
            if (pm.checkSignatures(D2Protocol.CONTROLLER, D2Protocol.HELPER) != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Controller signature changed");
            }
            boolean unlocked = context.getSystemService(UserManager.class).isUserUnlocked();
            context.startForegroundService(new Intent(D2Protocol.EARLY_ROOT_ACTION)
                    .putExtra(D2Protocol.EARLY_ROOT_EVENT, action)
                    .putExtra(D2Protocol.EARLY_ROOT_REQUEST_UNLOCKED, unlocked)
                    .setComponent(new ComponentName(D2Protocol.CONTROLLER, D2Protocol.EARLY_ROOT_SERVICE)));
            return true;
        } catch (Exception error) { Log.w("RMGUEarlyRoot", "Could not wake the root worker", error); return false; }
    }
}
