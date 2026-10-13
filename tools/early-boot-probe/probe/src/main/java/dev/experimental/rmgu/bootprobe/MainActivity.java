package dev.experimental.rmgu.bootprobe;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import dev.experimental.rmgu.bootprobe.shared.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView standing,observations,setup;
    private final List<Button> actions=new ArrayList<>();
    private boolean busy;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(245,247,250));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(22),dp(22),dp(22));scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v,i)->{Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());scroll.setPadding(b.left,b.top,b.right,b.bottom);return i;});
        text(body,"RMGU Boot Probe",27,true);text(body,"Privileged helper test · "+HelperProtocol.VERSION,14,false);
        text(body,"Test whether a helper inside Android’s system process can start before Samsung’s original boot animation exits. It records boot timing and tests IPsec; it does not run the root payload or hold the animation.",16,false);
        standing=text(body,"Checking helper…",15,true);
        text(body,"One-time setup",19,true);
        text(body,"1. Disable the previous accessibility probe. Root the phone normally in RMGU.\n2. Inspect the setup, then prepare the helper certificate.\n3. Fully reboot, install the embedded helper, and activate its startup hook.\n4. Keep RMGU Root on boot off. Fully reboot again and leave it locked for 90 seconds.\n5. Unlock and share the report.",15,false);
        text(body,"Preparation changes Android’s package-signature trust using existing root. An incorrect package-registry change can prevent Android from starting. This build checks the file, keeps verified backups, and refuses unsupported layouts. Avoid app installations during preparation and reboot promptly afterwards.",14,false);
        button(body,"Inspect setup (read-only, root)",()->root("inspect"));
        button(body,"Prepare helper certificate",()->new AlertDialog.Builder(this).setTitle("Prepare the privileged helper?")
                .setMessage("This adds only the embedded diagnostic helper’s certificate to Android’s system-UID trust. It modifies packages.xml and keeps a verified backup. A full reboot is required before installing the helper. Existing root access is required; this action does not reboot automatically.")
                .setNegativeButton("Cancel",null).setPositiveButton("Prepare",(d,w)->root("prepare")).show());
        button(body,"Install embedded helper",this::install);
        button(body,"Activate startup hook",()->operation("activate"));
        button(body,"Refresh observations",this::refresh);
        button(body,"Share report",this::share);
        text(body,"Cleanup",19,true);
        text(body,"When the test is finished, disable the startup hook. To remove the helper and its added certificate, root with RMGU again, use Remove helper setup, then fully reboot.",14,false);
        button(body,"Disable startup hook",()->operation("deactivate"));
        button(body,"Remove helper setup",()->new AlertDialog.Builder(this).setTitle("Remove the diagnostic helper?")
                .setMessage("Uses existing root to uninstall this helper and remove its recorded certificate from system-UID history. Other signatures and installed apps are preserved. Fully reboot afterwards to clear Android’s cached trust.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->root("remove")).show());
        text(body,"Setup result",19,true);setup=text(body,"No setup action yet.",13,false);setup.setTextIsSelectable(true);
        text(body,"Boot observations",19,true);observations=text(body,"Loading…",13,false);observations.setTypeface(Typeface.MONOSPACE);observations.setTextIsSelectable(true);
        setContentView(scroll);WindowInsetsController c=getWindow().getInsetsController();if(c!=null){int a=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.setSystemBarsAppearance(a,a);}
    }
    @Override protected void onResume(){super.onResume();refresh();}
    private String signer()throws Exception {
        Signature[] signatures=getPackageManager().getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners();
        if(signatures.length!=1)throw new IllegalStateException("Exactly one diagnostic signing certificate is required");return signatures[0].toCharsString().toLowerCase(Locale.ROOT);
    }
    private void setBusy(boolean value){busy=value;for(Button b:actions)b.setEnabled(!value);}
    private void root(String mode){
        if(busy)return;setBusy(true);setup.setText("Waiting for root approval and "+mode+"…");
        worker.execute(()->{
            File log=null;java.lang.Process child=null;String output;
            try {
                if(android.os.Process.myUid() / 100_000!=0)throw new IllegalStateException("Use the phone’s primary user for this diagnostic");
                verifyEmbedded();
                if(mode.equals("remove")){try{call("deactivate");}catch(Exception ignored){/* Uninstall also stops the binding. */}}
                log=File.createTempFile("root-setup-",".txt",createDeviceProtectedStorageContext().getCacheDir());
                child=new ProcessBuilder("su","-c",SetupCommand.command(getApplicationInfo().sourceDir,mode,signer())).redirectErrorStream(true).redirectOutput(log).start();
                if(!child.waitFor(120,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("Setup timed out. Inspect before repeating; an in-flight filesystem operation must not be assumed undone.");}
                output=TextFiles.read(log);
                int position=output.lastIndexOf("RMGU_SETUP_RESULT ");
                if(position>=0){JSONObject result=new JSONObject(output.substring(position+"RMGU_SETUP_RESULT ".length()).trim());
                    if(mode.equals("prepare")&&(result.optBoolean("ok")||result.optBoolean("committed")))createDeviceProtectedStorageContext().getSharedPreferences("helper-setup",MODE_PRIVATE).edit().putString("prepared_boot",result.optString("boot_key")).commit();
                }
            }catch(Exception error){output=error.getClass().getSimpleName()+": "+error.getMessage();}
            finally{if(child!=null&&child.isAlive())child.destroyForcibly();if(log!=null)log.delete();}
            try{TextFiles.write(new File(createDeviceProtectedStorageContext().getFilesDir(),"setup-result.txt"),output);}catch(IOException ignored){}
            String message=output;runOnUiThread(()->{if(isDestroyed())return;setBusy(false);setup.setText(message);refresh();});
        });
    }
    private File verifyEmbedded()throws Exception {
        File file=HelperApkProvider.staged(this);
        try(InputStream in=getAssets().open("privileged-helper.apk");FileOutputStream out=new FileOutputStream(file)){in.transferTo(out);}
        PackageInfo info=getPackageManager().getPackageArchiveInfo(file.getPath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(info==null||!HelperProtocol.HELPER.equals(info.packageName)||info.getLongVersionCode()!=HelperProtocol.VERSION_CODE||!"android.uid.system".equals(info.sharedUserId)||info.signingInfo==null)throw new IOException("Embedded helper identity is invalid");
        Signature[] certs=info.signingInfo.getApkContentsSigners();if(certs.length!=1||!certs[0].toCharsString().equalsIgnoreCase(signer()))throw new IOException("Controller/helper signing certificates differ");
        return file;
    }
    private void install(){
        if(busy)return;
        if(!getPackageManager().canRequestPackageInstalls()){new AlertDialog.Builder(this).setTitle("Allow helper installation")
                .setMessage("Allow this probe to install its embedded helper, then return and tap Install embedded helper again.")
                .setPositiveButton("Open setting",(d,w)->launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).setNegativeButton("Cancel",null).show();return;}
        setBusy(true);worker.execute(()->{
            try{
                String prepared=createDeviceProtectedStorageContext().getSharedPreferences("helper-setup",MODE_PRIVATE).getString("prepared_boot","");
                if(prepared.isEmpty()||prepared.equals("unknown")||ProbeStore.detectBootKey(this)==null||prepared.equals(ProbeStore.detectBootKey(this)))throw new IllegalStateException("Prepare the certificate, then perform a full reboot before installing the helper");
                verifyEmbedded();Uri uri=Uri.parse("content://"+HelperApkProvider.AUTHORITY+"/helper.apk");
                Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newUri(getContentResolver(),"Embedded diagnostic helper",uri));
                runOnUiThread(()->{if(!isDestroyed()){setBusy(false);launch(intent);}});
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){setBusy(false);setup.setText(error.getMessage());}});}
        });
    }
    private Bundle call(String method)throws Exception {
        PackageInfo info=getPackageManager().getPackageInfo(HelperProtocol.HELPER,0);
        if(info.applicationInfo==null||info.applicationInfo.uid!=1000||info.getLongVersionCode()!=HelperProtocol.VERSION_CODE||getPackageManager().checkSignatures(getPackageName(),HelperProtocol.HELPER)!=PackageManager.SIGNATURE_MATCH)throw new IOException("Matching system-UID helper is not installed");
        Bundle result=getContentResolver().call(Uri.parse("content://"+HelperProtocol.AUTHORITY),method,null,null);
        if(result==null)throw new IOException("Helper returned no response");if(result.containsKey("error"))throw new IOException(result.getString("error"));return result;
    }
    private void operation(String method){if(busy)return;setBusy(true);worker.execute(()->{String result;try{result=call(method).getString("status");}catch(Exception error){result=error.getMessage();}String finalResult=result;runOnUiThread(()->{if(!isDestroyed()){setBusy(false);setup.setText(finalResult);refresh();}});});}
    private void refresh(){
        if(busy)return;
        worker.execute(()->{String state,report;try{state=call("status").getString("status");report=call("report").getString("report");}catch(Exception error){state="Helper unavailable: "+error.getMessage();report="Complete the one-time setup to collect privileged startup observations.";}
            String a=state,b=report;runOnUiThread(()->{if(!isDestroyed()){standing.setText(a);int raw=b.indexOf("\n--- RAW OBSERVATIONS");observations.setText(raw<0?b:b.substring(0,raw));}});
        });
    }
    private void share(){if(busy)return;setBusy(true);worker.execute(()->{
        try{
            String report;try{report=call("report").getString("report");}catch(Exception error){report="Helper report unavailable: "+error.getMessage();}
            File setupFile=new File(createDeviceProtectedStorageContext().getFilesDir(),"setup-result.txt");
            if(setupFile.isFile())report+="\n\n--- LAST SETUP RESULT ---\n"+TextFiles.read(setupFile);
            File dir=new File(createDeviceProtectedStorageContext().getCacheDir(),"reports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create export");
            String name="report-"+UUID.randomUUID()+".txt";TextFiles.write(new File(dir,name),report);
            Uri uri=Uri.parse("content://"+ReportProvider.AUTHORITY+"/reports/"+name);
            Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newUri(getContentResolver(),"Privileged boot report",uri));
            runOnUiThread(()->{if(!isDestroyed()){setBusy(false);launch(Intent.createChooser(send,"Share boot report"));}});
        }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){setBusy(false);setup.setText("Export failed: "+error.getMessage());}});}
    });}
    private void launch(Intent intent){try{startActivity(intent);}catch(ActivityNotFoundException|SecurityException error){setup.setText("Android could not open this action: "+error.getMessage());}}
    private TextView text(LinearLayout parent,String content,int size,boolean bold){TextView v=new TextView(this);v.setText(content);v.setTextSize(size);v.setTextColor(Color.rgb(23,38,53));if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);parent.addView(v,p);return v;}
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);parent.addView(b,p);actions.add(b);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
