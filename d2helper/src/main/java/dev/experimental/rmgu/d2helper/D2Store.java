package dev.experimental.rmgu.d2helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import dev.experimental.rmgu.d2.VaultOperation;
import dev.experimental.rmgu.d2.VaultRecord;
import java.util.UUID;

final class D2Store {
    static final String SESSION = UUID.randomUUID().toString();
    private static volatile boolean observationSaved = true;
    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("rmgu_d2", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    static boolean activate(Context context) { return preferences(context).edit().putBoolean("activated", true).commit(); }
    static boolean setEnabled(Context context, boolean enabled) {
        return preferences(context).edit().putBoolean("enabled", enabled).commit();
    }
    static void record(Context context, String event, VaultOperation.Result result) {
        SharedPreferences.Editor edit = preferences(context).edit()
                .putString("session", SESSION).putLong("elapsed", SystemClock.elapsedRealtime())
                .putString("event", event).putString("state", result.state.name())
                .putString("detail", result.detail).putBoolean("verified", result.verified())
                .putBoolean("wrote", result.wrote);
        VaultRecord flags = result.record;
        observationSaved = edit.putInt("lock", flags == null ? -1 : flags.lock())
                .putInt("maintenance", flags == null ? -1 : flags.maintenance())
                .putInt("at", flags == null ? -1 : flags.at()).commit();
    }
    static Bundle snapshot(Context context) {
        SharedPreferences p = preferences(context);
        Bundle b = new Bundle();
        boolean current = SESSION.equals(p.getString("session", ""));
        b.putBoolean("enabled", enabled(context));
        b.putBoolean("activated", p.getBoolean("activated", false));
        b.putBoolean("current", current);
        b.putString("event", p.getString("event", ""));
        b.putString("state", p.getString("state", "UNCHECKED"));
        b.putString("detail", p.getString("detail", "No DMC observation yet"));
        b.putLong("age_ms", current ? Math.max(0, SystemClock.elapsedRealtime() - p.getLong("elapsed", 0)) : -1);
        b.putBoolean("verified", current && observationSaved && p.getBoolean("verified", false));
        b.putBoolean("observation_saved", observationSaved);
        b.putInt("lock", current ? p.getInt("lock", -1) : -1);
        b.putInt("maintenance", current ? p.getInt("maintenance", -1) : -1);
        b.putInt("at", current ? p.getInt("at", -1) : -1);
        return b;
    }
}
