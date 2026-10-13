package dev.experimental.rmgu.bootprobe;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeReportTest {
    private String ready(Object unlocked, boolean actual, String animation, String exit, String completed) throws Exception {
        JSONObject frame = new JSONObject().put("user_unlocked", unlocked)
                .put("real_wallpaper_engine_seen", actual).put("init.svc.bootanim", animation)
                .put("service.bootanim.exit", exit).put("sys.boot_completed", completed);
        JSONObject result = new JSONObject().put("ready", true).put("ready_ms", 1234)
                .put("snapshot_when_ready", frame);
        return new JSONObject().put("event", "ipsec_check_result").put("details", result).toString() + "\n";
    }
    @Test public void realLockedEarlySetupProducesCandidate() throws Exception {
        assertTrue(ProbeReport.build(ready(false, true, "running", "0", "0")).contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void previewOrUnknownUserStateCannotProduceCandidate() throws Exception {
        assertFalse(ProbeReport.build(ready(false, false, "running", "0", "0")).contains("CANDIDATE WINDOW OBSERVED"));
        assertFalse(ProbeReport.build(ready(JSONObject.NULL, true, "running", "0", "0")).contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void missingPropertiesStayQualifiedAsUncertain() throws Exception {
        String report = ProbeReport.build(ready(false, true, "running", "", ""));
        assertTrue(report.contains("POSSIBLE WINDOW"));
        assertFalse(report.contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void successfulLateSetupDoesNotBecomeEarlyEvidence() throws Exception {
        assertFalse(ProbeReport.build(ready(false, true, "running", "1", "0")).contains("CANDIDATE WINDOW OBSERVED"));
        assertFalse(ProbeReport.build(ready(false, true, "running", "0", "1")).contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void partialTrailingLineDoesNotDiscardRecordedEvidence() throws Exception {
        assertTrue(ProbeReport.build(ready(false, true, "running", "0", "0") + "{partial")
                .contains("CANDIDATE WINDOW OBSERVED"));
    }
    @Test public void aFailedReadinessCheckCannotQualify() throws Exception {
        String result = new JSONObject().put("event", "ipsec_check_result")
                .put("details", new JSONObject().put("ready", false).put("error_class", "SecurityException"))
                .toString() + "\n";
        assertFalse(ProbeReport.build(result).contains("CANDIDATE WINDOW OBSERVED"));
    }
}
