// SPDX-License-Identifier: GPL-2.0
package dev.experimental.rmgu.priorityprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String TARGET_PACKAGE = "dev.experimental.azhlroot";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText uidInput;
    private TextView results;
    private Button run;
    private String lastReport = "";

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size);
        v.setTextColor(Color.rgb(25,25,25)); v.setPadding(0,dp(8),0,dp(8)); return v;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20),dp(36),dp(20),dp(36)); layout.setBackgroundColor(Color.rgb(247,247,250));
        scroll.addView(layout); setContentView(scroll);
        layout.addView(text("Priority Probe", 26));
        layout.addView(text("Normal app sandbox · read-only queries\nOpen RMGU first. Confirm the target is visible as root before and after this check.",16));
        layout.addView(text("Target UID (RMGU on your phone: 10303)",15));
        uidInput = new EditText(this); uidInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        uidInput.setText(saved == null ? "10303" : saved.getString("target","10303"));
        uidInput.setSingleLine(true); layout.addView(uidInput);
        run = new Button(this); run.setText("Run checks"); layout.addView(run);
        Button copy = new Button(this); copy.setText("Copy results"); layout.addView(copy);
        results = text("Root permission is not used. This app runs two native query-only commands from its own app sandbox.\n\nA missing target can also return ESRCH, so a live root reference is required.",14);
        results.setTypeface(Typeface.MONOSPACE); results.setTextIsSelectable(true); layout.addView(results);
        run.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {begin();}
        });
        copy.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (!lastReport.isEmpty()) ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("Priority Probe",lastReport));
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("target",uidInput.getText().toString()); super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    private void begin() {
        final int target;
        try {
            target = Integer.parseInt(uidInput.getText().toString().trim());
            int appid=target%100000;
            if (appid<10000 || appid>19999 || target==Process.myUid()) throw new IllegalArgumentException();
        } catch (RuntimeException e) { results.setText("Enter a different ordinary app UID, such as 10303."); return; }
        run.setEnabled(false); results.setText("Running queries…");
        worker.execute(new Runnable() {
            @Override public void run() {
            String value;
            try { value = collect(target).toString(2); }
            catch (Exception e) { value = "Probe error: " + e.getClass().getSimpleName() + ": " + e.getMessage(); }
            final String report=value;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!isDestroyed()) {lastReport=report;results.setText(report);MainActivity.this.run.setEnabled(true);}
                }
            });
            }
        });
    }
    private JSONObject collect(int target) throws Exception {
        int uid=Process.myUid(),euid=Os.geteuid();
        String context=readFile("/proc/self/attr/current",512).trim();
        String status=readFile("/proc/self/status",16384);
        String capEff=statusValue(status,"CapEff:"), seccomp=statusValue(status,"Seccomp:");
        boolean normal=uid%100000>=10000 && uid%100000<=19999 && uid==euid &&
            context.startsWith("u:r:untrusted_app") && capEff.matches("0+");
        JSONObject nativeTarget=nativeQuery(target), nativeSelf=nativeQuery(uid);
        boolean complete=nativeTarget.has("query") && nativeSelf.has("query");
        boolean selfOk=false; int targetGp=-999,targetIo=-999;
        if (complete) {
            JSONObject t=nativeTarget.getJSONObject("query"),s=nativeSelf.getJSONObject("query");
            complete=t.optInt("schema",-1)==1 && s.optInt("schema",-1)==1 && t.optBoolean("query_only") && s.optBoolean("query_only") &&
                t.optInt("caller_uid",-1)==uid && s.optInt("caller_uid",-1)==uid &&
                t.optInt("target_uid",-1)==target && s.optInt("target_uid",-1)==uid;
            selfOk=s.optInt("getpriority",-999)>=0 && s.optInt("ioprio_get",-999)>=0;
            targetGp=t.optInt("getpriority",-999); targetIo=t.optInt("ioprio_get",-999);
        }
        JSONObject pm=new JSONObject();
        try {
            ApplicationInfo info=getPackageManager().getApplicationInfo(TARGET_PACKAGE,0);
            pm.put("visible",true).put("uid",info.uid).put("matches_target_uid",info.uid==target);
        } catch (PackageManager.NameNotFoundException e) {pm.put("visible",false);}
        catch (RuntimeException e) {pm.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
        JSONObject build;
        try (InputStream in=getAssets().open("probe-build.json")) {build=new JSONObject(readLimited(in,4096));}
        return new JSONObject().put("schema",1).put("probe_version","1.0").put("build",build)
            .put("package",getPackageName()).put("caller_uid",uid).put("effective_uid",euid)
            .put("selinux_context",context).put("effective_capabilities",capEff).put("seccomp",seccomp)
            .put("unprivileged_app_confirmed",normal).put("android_api",Build.VERSION.SDK_INT)
            .put("uptime_ms",SystemClock.elapsedRealtime()).put("target_uid",target)
            .put("rmgu_package_manager",pm)
            .put("native_target",nativeTarget).put("native_self",nativeSelf)
            .put("result",ProbeChecks.classify(normal,complete,selfOk,targetGp,targetIo))
            .put("live_root_reference_required",true)
            .put("scope","Native64 reads and package visibility; no write API or compat execution test");
    }
    private JSONObject nativeQuery(int uid) throws Exception {
        JSONObject result=new JSONObject(); java.lang.Process child=null;
        try {
            File executable=new File(getApplicationInfo().nativeLibraryDir,"libpriorityprobe.so");
            child=new ProcessBuilder(executable.getAbsolutePath(),"query",Integer.toString(uid)).redirectErrorStream(true).start();
            if (!child.waitFor(4,TimeUnit.SECONDS)) {
                child.destroyForcibly(); return result.put("error","Native query timed out");
            }
            String output;
            try (InputStream in=child.getInputStream()) {output=readLimited(in,8192).trim();}
            result.put("exit_code",child.exitValue());
            if (child.exitValue()==0) {
                try {result.put("query",new JSONObject(output));}
                catch (Exception e) {result.put("output",output).put("error","Native output was not valid JSON");}
            } else result.put("output",output).put("error","Native query exited before completion; sandbox/SELinux restrictions may need inspection");
        } catch (Exception e) {if (e instanceof InterruptedException) Thread.currentThread().interrupt();result.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
        finally {if (child!=null) child.destroy();}
        return result;
    }
    private String readFile(String path,int limit) {
        try (InputStream in=new FileInputStream(path)) {return readLimited(in,limit);}
        catch (Exception e) {return "unavailable";}
    }
    private String readLimited(InputStream in,int limit) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
        while ((n=in.read(buffer,0,Math.min(buffer.length,limit-bytes.size())))>0) bytes.write(buffer,0,n);
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private String statusValue(String status,String name) {
        for (String line:status.split("\n")) if (line.startsWith(name)) return line.substring(name.length()).trim();
        return "unavailable";
    }
}
