package dev.experimental.rmgu.d2helper;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import dev.experimental.rmgu.d2.D2Configuration;
import dev.experimental.rmgu.d2.VaultOperation;
import dev.experimental.rmgu.d2.VaultRecord;
import java.io.IOException;

final class D2Store {
    private static final D2Configuration configuration = new D2Configuration(new D2SettingsFile());
    private static volatile Observation observation;
    private static volatile String settingsError = "";
    private static final class Observation {
        final String event;
        final VaultOperation.Result result;
        final long elapsed = SystemClock.elapsedRealtime();
        Observation(String event, VaultOperation.Result result) { this.event = event; this.result = result; }
    }
    private static D2Configuration.State state() throws IOException {
        D2Configuration.State state = configuration.read();
        settingsError = "";
        return state;
    }
    static boolean enabled(Context ignored) {
        try { return state().enabled; }
        catch (IOException error) {
            settingsError = error.getMessage();
            Log.w("RMGUD2", "D2 settings unavailable; no flag write allowed", error);
            return false;
        }
    }
    static void activate(Context ignored) throws IOException { configuration.activate(); settingsError = ""; }
    static void setEnabled(Context ignored, boolean enabled) throws IOException {
        configuration.setEnabled(enabled);
        settingsError = "";
    }
    static void record(Context ignored, String event, VaultOperation.Result result) {
        // Live observations belong to this system-process session, not a possibly unwritable app directory.
        observation = new Observation(event, result);
    }
    static Bundle snapshot(Context ignored) {
        Bundle b = new Bundle();
        try {
            D2Configuration.State state = state();
            b.putBoolean("enabled", state.enabled);
            b.putBoolean("activated", state.activated);
        } catch (IOException error) { settingsError = error.getMessage(); }
        Observation current = observation;
        b.putBoolean("current", current != null);
        b.putString("event", current == null ? "" : current.event);
        b.putString("state", current == null ? "UNCHECKED" : current.result.state.name());
        String detail = current == null ? "No DMC observation yet" : current.result.detail;
        b.putString("detail", settingsError.isEmpty() ? detail : detail + "\nD2 settings: " + settingsError);
        b.putLong("age_ms", current == null ? -1 : Math.max(0, SystemClock.elapsedRealtime() - current.elapsed));
        b.putBoolean("verified", current != null && current.result.verified());
        b.putBoolean("observation_saved", true); // API 1 compatibility; observations deliberately stay in memory.
        VaultRecord flags = current == null ? null : current.result.record;
        b.putInt("lock", flags == null ? -1 : flags.lock());
        b.putInt("maintenance", flags == null ? -1 : flags.maintenance());
        b.putInt("at", flags == null ? -1 : flags.at());
        return b;
    }
}
