package dev.experimental.rmgu.bootprobe;
import dev.experimental.rmgu.bootprobe.shared.HelperEvidence;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class HelperEvidenceTest {
    private JSONObject frame()throws Exception{return new JSONObject().put("uid",1000).put("process","system_server").put("service_triggered",true).put("user_unlocked",false).put("init.svc.bootanim","running").put("service.bootanim.exit","0").put("sys.boot_completed","0").put("read_start_ms",1001).put("read_end_ms",1002);}
    private String records(JSONObject f)throws Exception{return new JSONObject().put("event","ipsec_result").put("boot_key","kernel-1").put("details",new JSONObject().put("ready",true).put("started_ms",990).put("ready_ms",1000).put("snapshot_when_ready",f)).put("snapshot",f)+"\n";}
    @Test public void correctSystemContextQualifies()throws Exception{assertTrue(HelperEvidence.qualifies(frame()));}
    @Test public void UidAloneDoesNotQualify()throws Exception{assertFalse(HelperEvidence.qualifies(frame().put("process","dev.experimental.helper")));assertFalse(HelperEvidence.qualifies(frame().put("uid",10000)));}
    @Test public void UnknownUserAndPropertiesCannotQualify()throws Exception{assertFalse(HelperEvidence.qualifies(frame().put("user_unlocked",JSONObject.NULL)));assertFalse(HelperEvidence.qualifies(frame().put("sys.boot_completed","")));}
    @Test public void ManualProviderResolutionCannotQualify()throws Exception{assertFalse(HelperEvidence.qualifies(frame().put("service_triggered",false)));}
    @Test public void EarlySuccessfulIpsecHasCandidateReport()throws Exception{assertTrue(HelperEvidence.report(records(frame()),"kernel-1").contains("CANDIDATE WINDOW"));}
    @Test public void StaleReportCannotClaimCurrentWindow()throws Exception{assertFalse(HelperEvidence.report(records(frame()),"kernel-2").contains("CANDIDATE WINDOW"));}
    @Test public void LateSuccessCannotQualify()throws Exception{assertFalse(HelperEvidence.report(records(frame().put("init.svc.bootanim","stopped")),"kernel-1").contains("CANDIDATE WINDOW"));}
    @Test public void EarlierObservedExitBlocksLaterAnimation()throws Exception{String exit=new JSONObject().put("event","snapshot").put("boot_key","kernel-1").put("snapshot",frame().put("read_start_ms",500).put("init.svc.bootanim","stopped"))+"\n";assertFalse(HelperEvidence.report(exit+records(frame()),"kernel-1").contains("CANDIDATE WINDOW"));}
    @Test public void UnknownPropertiesReportAnUncertainRunningWindow()throws Exception{assertTrue(HelperEvidence.report(records(frame().put("sys.boot_completed","")),"kernel-1").contains("POSSIBLE EARLY WINDOW"));}
    @Test public void MissingOrReversedReadIntervalsCannotQualify()throws Exception{assertFalse(HelperEvidence.qualifies(frame().put("read_end_ms",1000)));JSONObject missing=frame();missing.remove("read_start_ms");assertFalse(HelperEvidence.qualifies(missing));}
    @Test public void MissingIdentityInAnyRecordBlocksWindow()throws Exception{String missing=new JSONObject().put("event","snapshot").put("snapshot",frame())+"\n";assertTrue(HelperEvidence.report(records(frame())+missing,"kernel-1").contains("INCONCLUSIVE"));}
    @Test public void UnidentifiedBootCannotClaimWindow()throws Exception{assertTrue(HelperEvidence.report(records(frame()).replace("kernel-1","unidentified-segment"),null).contains("INCONCLUSIVE"));}
    @Test public void MalformedRecordBlocksWindow()throws Exception{assertTrue(HelperEvidence.report(records(frame())+"{incomplete", "kernel-1").contains("INCONCLUSIVE"));}
    @Test public void ReadinessSnapshotAloneVerifiesContext()throws Exception{JSONObject e=new JSONObject(records(frame()));e.remove("snapshot");assertTrue(HelperEvidence.report(e+"\n","kernel-1").contains("CANDIDATE WINDOW"));}
    @Test public void ReadinessBeforeAttemptOrAfterSnapshotCannotClaimWindow()throws Exception{JSONObject e=new JSONObject(records(frame()));e.getJSONObject("details").put("started_ms",1003);assertFalse(HelperEvidence.report(e+"\n","kernel-1").contains("CANDIDATE WINDOW"));e.getJSONObject("details").put("started_ms",990).put("ready_ms",1004);assertFalse(HelperEvidence.report(e+"\n","kernel-1").contains("CANDIDATE WINDOW"));}
}
