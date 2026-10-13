package dev.experimental.rmgu.bootprobe;

import org.json.JSONObject;
import java.util.Locale;

final class ProbeReport {
    static String build(String records) throws Exception {
        if (records.isEmpty()) return "No observations yet. Select the test wallpaper, then perform a full reboot.";
        String boot = "unknown", device = "unknown";
        long realEngineMs = -1, readyMs = -1, exitMs = -1;
        boolean candidate = false, runningHint = false, ready = false, propertiesReadable = false;
        String lastFailure = "";
        int attempts = 0;
        for (String line : records.split("\n")) {
            if (line.isBlank()) continue;
            JSONObject event;
            try { event = new JSONObject(line); }
            catch (Exception ignored) { continue; } // A killed process can leave one incomplete line.
            boot = event.optString("boot_key", boot);
            String type = event.optString("event");
            if (type.equals("process_started")) device = event.optString("model", device);
            JSONObject details = event.optJSONObject("details");
            if (type.equals("wallpaper_engine_created") && details != null
                    && !details.optBoolean("preview", true) && realEngineMs < 0)
                realEngineMs = event.optLong("elapsed_ms", -1);
            JSONObject snapshot = event.optJSONObject("snapshot");
            if (snapshot != null) {
                propertiesReadable |= !snapshot.optString("init.svc.bootanim").isEmpty();
                if (exitMs < 0 && snapshot.optString("classification").equals("EXITING_OR_FINISHED"))
                    exitMs = snapshot.optLong("property_read_end_ms", -1);
            }
            if (!type.equals("ipsec_check_result") || details == null) continue;
            attempts++;
            if (!details.optBoolean("ready")) {
                lastFailure = details.optString("error_class", "Not ready") + ": " + details.optString("error");
                continue;
            }
            ready = true;
            JSONObject frame = details.optJSONObject("snapshot_when_ready");
            if (frame == null) continue;
            BootEvidence.Window window = BootEvidence.classify(frame.optString("init.svc.bootanim"),
                    frame.optString("service.bootanim.exit"), frame.optString("sys.boot_completed"));
            boolean locked = Boolean.FALSE.equals(frame.opt("user_unlocked"));
            boolean actualEngine = frame.optBoolean("real_wallpaper_engine_seen");
            boolean qualifies = locked && BootEvidence.candidate(!actualEngine, false, true, window);
            if (qualifies) {
                candidate = true;
                if (readyMs < 0) readyMs = details.optLong("ready_ms", -1);
            }
            runningHint |= locked && actualEngine && window == BootEvidence.Window.RUNNING_UNCERTAIN;
        }
        String conclusion = candidate
                ? "CANDIDATE WINDOW OBSERVED: IPsec setup succeeded while locked, with a real wallpaper engine and pre-exit boot-animation signals."
                : runningHint
                ? "POSSIBLE WINDOW: IPsec setup succeeded with the animation service running, but some boot properties were unavailable."
                : !propertiesReadable
                ? "INCONCLUSIVE: the boot-animation property was unavailable to this app."
                : "EARLY WINDOW NOT DEMONSTRATED in these observations.";
        String summary = "RMGU Early Boot Probe 0.1.0\n"
                + "Device: " + device + "\nBoot identity: " + boot + "\n\n"
                + conclusion + "\n\n"
                + "First real wallpaper engine: " + time(realEngineMs) + "\n"
                + "First observed animation exit / boot completion: " + time(exitMs) + "\n"
                + "IPsec setup succeeded: " + ready + " (" + attempts + " completed checks)\n"
                + "Qualified IPsec readiness: " + time(readyMs) + "\n"
                + (lastFailure.isEmpty() ? "" : "Last IPsec failure: " + lastFailure + "\n")
                + "\nThese are app-visible observations, not proof of successful rooting or Samsung's exact visible screen timing.\n"
                + "Empty property values mean absent or inaccessible; they are never treated as zero.\n"
                + "Property intervals are recorded explicitly. Wallpaper previews cannot qualify.\n"
                + "This build has no rooting payload, property writes, boot delay, or automatic restart.\n"
                + "Temporary IPsec resources are closed without applying a transform to any network socket.\n";
        return summary + "\n--- RAW OBSERVATIONS (JSONL) ---\n" + records;
    }

    static String summary(String report) {
        int raw = report.indexOf("\n--- RAW OBSERVATIONS");
        return raw < 0 ? report : report.substring(0, raw);
    }

    private static String time(long ms) {
        return ms < 0 ? "not observed" : String.format(Locale.US, "%.3f s after kernel boot", ms / 1000.0);
    }
}
