package dev.experimental.rmgu.bootprobe;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeReportTest {
    private static final String BOOT = "count-7";
    private static final String SEGMENT = "segment-a";

    private JSONObject frame(Object unlocked, boolean connected, String animation, String exit,
            String completed, long start) throws Exception {
        return new JSONObject().put("user_unlocked", unlocked)
                .put("accessibility_service_connected", connected).put("init.svc.bootanim", animation)
                .put("service.bootanim.exit", exit).put("sys.boot_completed", completed)
                .put("property_read_start_ms", start).put("property_read_end_ms", start + 1);
    }
    private JSONObject early(long start) throws Exception { return frame(false, true, "running", "0", "0", start); }
    private JSONObject event(String type, long elapsed) throws Exception {
        return new JSONObject().put("event", type).put("elapsed_ms", elapsed)
                .put("segment", SEGMENT).put("boot_key", BOOT);
    }
    private String line(JSONObject value) { return value + "\n"; }
    private String connection() throws Exception { return line(event("accessibility_service_connected", 1000)); }
    private JSONObject result(JSONObject frame) throws Exception {
        return event("ipsec_check_result", 1300).put("details", new JSONObject()
                .put("ready", true).put("ready_ms", 1200).put("snapshot_when_ready", frame));
    }
    private String report(String records) throws Exception { return ProbeReport.build(records, BOOT); }
    private String ready(JSONObject frame) throws Exception { return connection() + line(result(frame)); }
    private void noCandidate(String records) throws Exception {
        assertFalse(report(records).contains("CANDIDATE WINDOW OBSERVED"));
    }

    @Test public void connectedLockedEarlyIpsecProducesCandidate() throws Exception {
        assertTrue(report(ready(early(1201))).contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void readinessWithoutRecordedConnectionCannotQualify() throws Exception {
        noCandidate(line(result(early(1201))));
    }
    @Test public void connectionFromAnotherProcessCannotQualify() throws Exception {
        noCandidate(connection() + line(result(early(1201)).put("segment", "segment-b")));
    }
    @Test public void disconnectedServiceCannotQualify() throws Exception {
        noCandidate(ready(frame(false, false, "running", "0", "0", 1201)));
    }
    @Test public void unknownOrUnlockedUserCannotQualify() throws Exception {
        noCandidate(ready(frame(JSONObject.NULL, true, "running", "0", "0", 1201)));
        noCandidate(ready(frame(true, true, "running", "0", "0", 1201)));
    }
    @Test public void missingPropertiesRemainUncertain() throws Exception {
        String records = ready(frame(false, true, "running", "", "", 1201));
        assertTrue(report(records).contains("POSSIBLE EARLY SERVICE"));
        noCandidate(records);
    }
    @Test public void lateSuccessCannotBecomeEarlyEvidence() throws Exception {
        noCandidate(ready(frame(false, true, "running", "1", "0", 1201)));
        noCandidate(ready(frame(false, true, "running", "0", "1", 1201)));
        noCandidate(ready(frame(false, true, "stopped", "0", "0", 1201)));
    }
    @Test public void staleBootIsExplicitAndCannotClaimCurrentCandidate() throws Exception {
        String result = ProbeReport.build(ready(early(1201)), "count-8");
        assertTrue(result.contains("PREVIOUS BOOT REPORT"));
        assertFalse(result.contains("CANDIDATE WINDOW OBSERVED"));
        assertTrue(result.contains("Observed pre-exit span after IPsec readiness: not established"));
    }
    @Test public void mixedBootsAreNotCombined() throws Exception {
        String records = connection() + line(result(early(1201)).put("boot_key", "count-8"));
        assertTrue(report(records).contains("records contain different boot identities"));
        noCandidate(records);
    }
    @Test public void partialTrailingLineDoesNotEraseEvidence() throws Exception {
        assertTrue(report(ready(early(1201)) + "{partial").contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void failedReadinessCanStillReportEarlyService() throws Exception {
        String records = connection() + line(event("boot_snapshot", 1200).put("snapshot", early(1200)))
                + line(event("ipsec_check_result", 1300).put("details", new JSONObject()
                        .put("ready", false).put("error_class", "SecurityException")));
        assertTrue(report(records).contains("EARLY SERVICE OBSERVED"));
        noCandidate(records);
    }
    @Test public void noConnectionReportsBroadcastOnlyBaseline() throws Exception {
        String records = line(event("locked_boot_completed_received", 1200)
                .put("snapshot", frame(false, false, "stopped", "1", "1", 1200)));
        assertTrue(report(records).contains("NO ACCESSIBILITY CONNECTION RECORDED"));
    }
    @Test public void unavailableAnimationPropertyIsInconclusive() throws Exception {
        String records = ready(frame(false, true, "", "", "", 1201));
        assertTrue(report(records).contains("INCONCLUSIVE"));
        noCandidate(records);
    }
    @Test public void aLaterAnimationAfterAnObservedExitCannotQualify() throws Exception {
        String records = line(event("boot_snapshot", 500)
                .put("snapshot", frame(false, false, "stopped", "1", "1", 500)))
                + ready(early(1201));
        noCandidate(records);
    }
    @Test public void readinessSnapshotBeforeResourceSuccessCannotQualify() throws Exception {
        noCandidate(ready(early(1190)));
    }
    @Test public void invalidPropertyIntervalsCannotQualify() throws Exception {
        noCandidate(ready(early(1201).put("property_read_end_ms", 1190)));
        noCandidate(ready(early(1201).put("property_read_start_ms", -1)));
    }
    @Test public void readinessBeforeConnectionCannotQualify() throws Exception {
        String records = line(event("accessibility_service_connected", 2000)) + line(result(early(1201)));
        noCandidate(records);
    }
    @Test public void storedClassificationCannotOverrideActualProperties() throws Exception {
        noCandidate(ready(frame(false, true, "stopped", "1", "1", 1201).put("classification", "EARLY")));
    }
    @Test public void remainingSpanUsesPreExitSamplesInsteadOfLateExitTimestamp() throws Exception {
        String records = ready(early(1201))
                + line(event("boot_snapshot", 3000).put("snapshot", early(3000)))
                + line(event("boot_snapshot", 8000).put("snapshot", frame(false, true, "stopped", "1", "1", 8000)));
        assertTrue(report(records).contains("at least 1.798 s between recorded pre-exit samples"));
    }
    @Test public void noLaterPreExitSampleDoesNotInventAvailableTime() throws Exception {
        assertTrue(report(ready(early(1201))).contains("Observed pre-exit span after IPsec readiness: not established"));
    }
    @Test public void unknownCurrentBootIdentityIsDisclosed() throws Exception {
        assertTrue(ProbeReport.build(ready(early(1201)), null).contains("current-boot match cannot be verified"));
    }
    @Test public void emptyAndTruncatedReportsGiveSetupInstructions() throws Exception {
        assertTrue(report("").contains("Enable RMGU Boot Probe in Accessibility"));
        assertTrue(report("{partial").contains("No complete observations"));
    }
}
