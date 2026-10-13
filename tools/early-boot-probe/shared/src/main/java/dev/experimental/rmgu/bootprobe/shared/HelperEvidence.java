package dev.experimental.rmgu.bootprobe.shared;

import org.json.JSONObject;
import java.util.*;

public final class HelperEvidence {
    private HelperEvidence() { }
    public static boolean systemServer(JSONObject frame) {
        return frame != null && frame.optInt("uid", -1) == 1000
                && "system_server".equals(frame.optString("process"));
    }
    public static boolean early(JSONObject frame) {
        return frame != null && "running".equals(frame.optString("init.svc.bootanim"))
                && "0".equals(frame.optString("service.bootanim.exit"))
                && "0".equals(frame.optString("sys.boot_completed"));
    }
    public static boolean exited(JSONObject frame) {
        if (frame == null) return false;
        String state=frame.optString("init.svc.bootanim");
        return "stopped".equals(state) || "stopping".equals(state)
                || "1".equals(frame.optString("service.bootanim.exit"))
                || "1".equals(frame.optString("sys.boot_completed"));
    }
    public static boolean qualifies(JSONObject frame) {
        return systemServer(frame) && frame.optBoolean("service_triggered")
                && Boolean.FALSE.equals(frame.opt("user_unlocked")) && early(frame) && interval(frame);
    }
    private static boolean interval(JSONObject frame) {
        return frame != null && frame.optLong("read_start_ms", -1) >= 0
                && frame.optLong("read_end_ms", -1) >= frame.optLong("read_start_ms", -1);
    }
    public static String report(String records, String currentBoot) throws Exception {
        if (records.isBlank()) return "No helper startup observations yet. Activate the startup hook, then fully reboot.";
        List<JSONObject> events=new ArrayList<>();
        Set<String> boots=new HashSet<>();
        long create=-1, connect=-1, firstAttempt=-1, success=-1, firstExit=-1, candidate=-1, earlyService=-1, runningHint=-1;
        boolean privileged=false, sawAnimation=false, invalidIdentity=false, malformed=false;
        int attempts=0;
        String lastError="", device="unknown", process="unknown", recorded="unknown";
        for(String line:records.split("\n")) {
            if(line.isBlank()) continue;
            try {
                JSONObject e=new JSONObject(line); events.add(e);
                String boot=e.optString("boot_key");
                if(!boot.isEmpty()){boots.add(boot);recorded=boot;}
                if(!boot.startsWith("kernel-") || boot.length()<=7) invalidIdentity=true;
                String kind=e.optString("event"); long time=e.optLong("elapsed_ms",-1);
                if(kind.equals("condition_service_created")) create=first(create,time);
                if(kind.equals("condition_service_connected")) connect=first(connect,time);
                if(kind.equals("ipsec_started")) firstAttempt=first(firstAttempt,time);
                device=e.optString("model",device);
                JSONObject details=e.optJSONObject("details");
                for(JSONObject f:new JSONObject[]{e.optJSONObject("snapshot"),details==null?null:details.optJSONObject("snapshot_when_ready")}) {
                    if(f==null) continue;
                    process=f.optString("process",process); privileged|=systemServer(f);
                    sawAnimation|=!f.optString("init.svc.bootanim").isEmpty();
                    if(interval(f)&&systemServer(f)&&f.optBoolean("service_triggered")&&Boolean.FALSE.equals(f.opt("user_unlocked"))&&"running".equals(f.optString("init.svc.bootanim"))&&!exited(f)) runningHint=first(runningHint,f.optLong("read_end_ms",-1));
                    if(interval(f)&&exited(f)) firstExit=first(firstExit,f.optLong("read_start_ms",-1));
                }
            } catch(Exception ignored) { malformed=true; }
        }
        for(JSONObject e:events) {
            JSONObject f=e.optJSONObject("snapshot");
            if(f!=null && qualifies(f)) earlyService=first(earlyService,f.optLong("read_end_ms",-1));
            if(!e.optString("event").equals("ipsec_result")) continue;
            attempts++; JSONObject d=e.optJSONObject("details");
            if(d==null)continue;
            if(!d.optBoolean("ready")){lastError=d.optString("error");continue;}
            long time=d.optLong("ready_ms",-1);success=first(success,time);
            JSONObject readyFrame=d.optJSONObject("snapshot_when_ready");
            if(qualifies(readyFrame) && d.optLong("started_ms",-1)>=0 && time>=d.optLong("started_ms",-1) && readyFrame.optLong("read_start_ms",-1)>=time)
                candidate=first(candidate,readyFrame.optLong("read_end_ms",-1));
        }
        if(firstExit>=0){if(candidate>=firstExit)candidate=-1;if(earlyService>=firstExit)earlyService=-1;if(runningHint>=firstExit)runningHint=-1;}
        boolean stale=currentBoot!=null&&!currentBoot.equals(recorded);
        String conclusion=malformed?"INCONCLUSIVE: an observation record is incomplete or malformed."
                : invalidIdentity||boots.size()!=1?"INCONCLUSIVE: boot identities are missing or mixed."
                : stale?"PREVIOUS BOOT REPORT: current helper startup was not recorded."
                : !privileged?"PRIVILEGED CONTEXT NOT VERIFIED: expected UID 1000 inside system_server."
                : candidate>=0?"CANDIDATE WINDOW OBSERVED: IPsec succeeded in system_server while locked and before observed animation exit."
                : earlyService>=0?"EARLY HELPER OBSERVED: its startup hook ran during the pre-exit window; early IPsec readiness was not demonstrated."
                : runningHint>=0?"POSSIBLE EARLY WINDOW: the animation service was running while locked, but explicit pre-exit properties were unavailable."
                : !sawAnimation?"INCONCLUSIVE: animation service state was unavailable."
                : "EARLY WINDOW NOT DEMONSTRATED: the helper did not establish usable IPsec before observed animation exit.";
        return "RMGU Privileged Boot Probe "+HelperProtocol.VERSION+"\nDevice: "+device
                +"\nRecorded boot: "+recorded+"\nCurrent boot: "+(currentBoot==null?"unavailable":currentBoot)
                +"\nProcess: "+process+"\n\n"+conclusion+"\n\n"
                +"First service creation: "+time(create)+"\nFirst system connection: "+time(connect)
                +"\nFirst IPsec attempt: "+time(firstAttempt)+"\nFirst IPsec success: "+time(success)
                +"\nFirst observed animation exit: "+time(firstExit)+"\nQualified IPsec observation: "+time(candidate)
                +"\nCompleted IPsec checks: "+attempts+(lastError.isEmpty()?"":"\nLast IPsec error: "+lastError)
                +"\n\nThe first success is an observation, not the instant IPsec became available."
                +"\nNo root payload, animation delay, DND mode change, notification access, or automatic reboot is performed."
                +"\nEmpty properties remain unknown. Observations cannot prove the exact visible screen or successful root."
                +"\n\n--- RAW OBSERVATIONS (JSONL) ---\n"+records;
    }
    private static long first(long a,long b){return b<0?a:a<0?b:Math.min(a,b);}
    private static String time(long value){return value<0?"not observed":String.format(Locale.US,"%.3f s after kernel boot",value/1000.0);}
}
