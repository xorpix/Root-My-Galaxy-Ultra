package dev.experimental.rmgu.bootprobe;

import org.json.JSONObject;
import java.util.*;

/** Reports observed timing only; a connected service and IPsec success are both required. */
final class ProbeReport {
    static final String VERSION = "0.2.0";

    private static final class Observation {
        final String segment;
        final long time;
        Observation(String segment, long time) { this.segment = segment; this.time = time; }
    }

    private static final class Evidence {
        long exitMs = -1, exitBoundaryMs = -1;
        boolean propertiesReadable;
        final List<Observation> early = new ArrayList<>();
        final List<Observation> uncertain = new ArrayList<>();

        BootEvidence.Window frame(JSONObject frame, String segment) {
            if (frame == null) return BootEvidence.Window.UNKNOWN;
            propertiesReadable |= !frame.optString("init.svc.bootanim").isEmpty();
            BootEvidence.Window window = BootEvidence.classify(frame.optString("init.svc.bootanim"),
                    frame.optString("service.bootanim.exit"), frame.optString("sys.boot_completed"));
            long start = frame.optLong("property_read_start_ms", -1);
            long end = frame.optLong("property_read_end_ms", -1);
            if (window == BootEvidence.Window.EXITING_OR_FINISHED) {
                exitMs = first(exitMs, end);
                exitBoundaryMs = first(exitBoundaryMs, start >= 0 ? start : end);
            }
            if (start < 0 || end < start) return BootEvidence.Window.UNKNOWN;
            if (frame.optBoolean("accessibility_service_connected")
                    && Boolean.FALSE.equals(frame.opt("user_unlocked"))) {
                if (window == BootEvidence.Window.EARLY) early.add(new Observation(segment, start));
                if (window == BootEvidence.Window.RUNNING_UNCERTAIN)
                    uncertain.add(new Observation(segment, start));
            }
            return window;
        }
    }

    static String build(String records, String currentBootKey) throws Exception {
        if (records.isBlank()) return "No observations yet. Enable RMGU Boot Probe in Accessibility, then perform a full reboot.";
        String boot = "unknown", device = "unknown";
        long createdMs = -1, connectedMs = -1, lockedBootMs = -1, bootCompletedMs = -1;
        long firstIpsecMs = -1;
        int attempts = 0, validRecords = 0;
        String lastFailure = "";
        boolean ready = false;
        Set<String> boots = new HashSet<>();
        Map<String, Long> connectedSegments = new HashMap<>();
        Evidence evidence = new Evidence();
        List<Observation> readyCandidates = new ArrayList<>();
        for (String line : records.split("\n")) {
            if (line.isBlank()) continue;
            JSONObject event;
            try { event = new JSONObject(line); }
            catch (Exception ignored) { continue; } // A killed process may leave one incomplete line.
            validRecords++;
            String key = event.optString("boot_key");
            if (!key.isEmpty()) { boots.add(key); boot = key; }
            String segment = event.optString("segment");
            String type = event.optString("event");
            long elapsed = event.optLong("elapsed_ms", -1);
            if (type.equals("process_started")) device = event.optString("model", device);
            if (type.equals("accessibility_service_created")) createdMs = first(createdMs, elapsed);
            if (type.equals("accessibility_service_connected")) {
                connectedMs = first(connectedMs, elapsed);
                if (!segment.isEmpty() && elapsed >= 0)
                    connectedSegments.merge(segment, elapsed, Math::min);
            }
            if (type.equals("locked_boot_completed_received")) lockedBootMs = first(lockedBootMs, elapsed);
            if (type.equals("boot_completed_received")) bootCompletedMs = first(bootCompletedMs, elapsed);
            evidence.frame(event.optJSONObject("snapshot"), segment);
            JSONObject details = event.optJSONObject("details");
            if (!type.equals("ipsec_check_result") || details == null) continue;
            attempts++;
            if (!details.optBoolean("ready")) {
                lastFailure = details.optString("error_class", "Not ready") + ": " + details.optString("error");
                continue;
            }
            ready = true;
            long readyMs = details.optLong("ready_ms", -1);
            firstIpsecMs = first(firstIpsecMs, readyMs);
            JSONObject frame = details.optJSONObject("snapshot_when_ready");
            BootEvidence.Window window = evidence.frame(frame, segment);
            if (frame == null || readyMs < 0 || frame.optLong("property_read_start_ms", -1) < readyMs) continue;
            if (BootEvidence.candidate(frame.optBoolean("accessibility_service_connected"),
                    Boolean.FALSE.equals(frame.opt("user_unlocked")), true, window)) {
                // Completion of the property read is the conservative start of any remaining span.
                readyCandidates.add(new Observation(segment, frame.optLong("property_read_end_ms", -1)));
            }
        }
        if (validRecords == 0) return "No complete observations could be read. Enable the service and perform a full reboot.";
        boolean mixed = boots.size() > 1;
        if (mixed) return "INCONCLUSIVE: records contain different boot identities; timing was not combined.\n"
                + "\n--- RAW OBSERVATIONS (JSONL) ---\n" + records;
        boolean stale = currentBootKey != null && !currentBootKey.equals(boot);
        long qualifiedMs = firstValid(readyCandidates, connectedSegments, evidence.exitBoundaryMs);
        long earlyMs = firstValid(evidence.early, connectedSegments, evidence.exitBoundaryMs);
        boolean uncertain = firstValid(evidence.uncertain, connectedSegments, evidence.exitBoundaryMs) >= 0;
        String conclusion;
        if (stale) conclusion = "PREVIOUS BOOT REPORT: no report from the current boot is available. Check that the service is enabled, then fully reboot.";
        else if (qualifiedMs >= 0) conclusion = "CANDIDATE WINDOW OBSERVED: IPsec setup succeeded while the accessibility service was connected, the phone was locked, and all three boot signals indicated a pre-exit animation.";
        else if (earlyMs >= 0) conclusion = "EARLY SERVICE OBSERVED: the service connected before the observed animation exit, but IPsec readiness in that window was not demonstrated.";
        else if (uncertain) conclusion = "POSSIBLE EARLY SERVICE: the animation service was running while locked, but some boot properties were unavailable.";
        else if (connectedMs < 0) conclusion = "NO ACCESSIBILITY CONNECTION RECORDED: boot broadcasts alone do not demonstrate this startup route.";
        else if (!evidence.propertiesReadable) conclusion = "INCONCLUSIVE: the boot-animation service property was unavailable to this app.";
        else conclusion = "EARLY WINDOW NOT DEMONSTRATED in these observations.";

        long lastEarlyMs = -1;
        if (!stale && !mixed && qualifiedMs >= 0) for (Observation observation : evidence.early)
            if (valid(observation, connectedSegments, evidence.exitBoundaryMs) && observation.time >= qualifiedMs)
                lastEarlyMs = Math.max(lastEarlyMs, observation.time);
        String span = lastEarlyMs < 0 ? "not established"
                : String.format(Locale.US, "at least %.3f s between recorded pre-exit samples",
                        (lastEarlyMs - qualifiedMs) / 1000.0);
        String summary = "RMGU Early Boot Probe " + VERSION + " — accessibility\n"
                + "Device: " + device + "\nRecorded boot: " + boot + "\n"
                + "Current boot: " + (currentBootKey == null ? "unavailable; current-boot match cannot be verified" : currentBootKey) + "\n\n"
                + conclusion + "\n\n"
                + "First service creation: " + time(createdMs) + "\n"
                + "First accessibility connection: " + time(connectedMs) + "\n"
                + "First qualified early service sample: " + time(earlyMs) + "\n"
                + "LOCKED_BOOT_COMPLETED received: " + time(lockedBootMs) + "\n"
                + "BOOT_COMPLETED received: " + time(bootCompletedMs) + "\n"
                + "First observed animation exit / boot completion: " + time(evidence.exitMs) + "\n"
                + "IPsec setup succeeded: " + ready + " (" + attempts + " completed checks)\n"
                + "First IPsec success: " + time(firstIpsecMs) + "\n"
                + "Qualified pre-exit IPsec observation: " + time(qualifiedMs) + "\n"
                + "Observed pre-exit span after IPsec readiness: " + span + "\n"
                + (lastFailure.isEmpty() ? "" : "Last IPsec failure: " + lastFailure + "\n")
                + "\nThese are app-visible observations, not proof of successful rooting or the exact frame shown on Samsung’s screen.\n"
                + "Property reads have explicit intervals. Empty values are never treated as zero; unknown lock state cannot qualify.\n"
                + "A connection from another process segment or an animation seen after an earlier exit cannot qualify.\n"
                + "The probe uses device-protected storage, subscribes to no accessibility events, and requests no screen or gesture capabilities.\n"
                + "It contains no rooting payload, root commands, property writes, boot delay, or automatic restart.\n"
                + "Temporary IPsec resources are closed without applying a transform to any network socket.\n";
        return summary + "\n--- RAW OBSERVATIONS (JSONL) ---\n" + records;
    }

    private static boolean valid(Observation observation, Map<String, Long> segments, long exitMs) {
        Long connection = segments.get(observation.segment);
        return connection != null && observation.time >= connection
                && (exitMs < 0 || observation.time < exitMs);
    }

    private static long firstValid(List<Observation> observations, Map<String, Long> segments, long exitMs) {
        long result = -1;
        for (Observation observation : observations)
            if (valid(observation, segments, exitMs)) result = first(result, observation.time);
        return result;
    }

    private static long first(long current, long value) {
        return value < 0 ? current : current < 0 ? value : Math.min(current, value);
    }

    static String summary(String report) {
        int raw = report.indexOf("\n--- RAW OBSERVATIONS");
        return raw < 0 ? report : report.substring(0, raw);
    }

    private static String time(long ms) {
        return ms < 0 ? "not observed" : String.format(Locale.US, "%.3f s after kernel boot", ms / 1000.0);
    }
}
