package dev.experimental.rmgu.boothelper;

import android.content.Context;
import android.net.*;
import android.os.*;
import android.util.Log;
import dev.experimental.rmgu.bootprobe.shared.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java-only diagnostics. The service thread never performs I/O or IPsec work. */
final class HelperRuntime {
    private static final String TAG="RMGUPrivBoot";
    private static final String SEGMENT=UUID.randomUUID().toString();
    private static final int MAX_BYTES=200_000;
    private static final AtomicBoolean triggered=new AtomicBoolean(),started=new AtomicBoolean(),busy=new AtomicBoolean();
    private static final ScheduledExecutorService timeline=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"rmgu-priv-timing");t.setDaemon(true);return t;});
    private static final ExecutorService checks=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"rmgu-priv-ipsec");t.setDaemon(true);return t;});
    private static long start,lastWrite,nextCheck;
    private static int attempts;
    private static boolean ready;
    private static String lastState="";
    private static File file;
    private static String boot;
    private static ScheduledFuture<?> sampler;

    static void trigger(Context context,String event) {
        triggered.set(true);
        long elapsed=SystemClock.elapsedRealtime();
        Context app=context.getApplicationContext();
        timeline.execute(()->{
            try {
                initialize(app);
                write(base(event,elapsed).put("snapshot",snapshot(app)));
                if(started.compareAndSet(false,true)) {
                    start=SystemClock.elapsedRealtime();
                    sampler=timeline.scheduleWithFixedDelay(()->poll(app),0,100,TimeUnit.MILLISECONDS);
                }
            }catch(Throwable error){Log.e(TAG,"Observation failed",error);}
        });
    }
    static String bootKey() {
        try {
            String id=read(new File("/proc/sys/kernel/random/boot_id")).trim();
            return id.matches("[0-9a-f-]{36}")?"kernel-"+id:null;
        }catch(Exception ignored){return null;}
    }
    private static void initialize(Context context)throws Exception {
        if(file!=null)return;
        boot=bootKey(); if(boot==null)boot="unidentified-"+SEGMENT;
        File dir=new File(HelperProtocol.DIRECTORY,"observations");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create diagnostic directory");
        file=new File(dir,"boot-"+boot+".jsonl");
        File[] old=dir.listFiles((d,n)->n.matches("boot-[a-z0-9-]+\\.jsonl"));
        if(old!=null){java.util.Arrays.sort(old,java.util.Comparator.comparingLong(File::lastModified).reversed());for(int i=4;i<old.length;i++)if(!old[i].equals(file))old[i].delete();}
        write(base("helper_process_observed",SystemClock.elapsedRealtime()).put("snapshot",snapshot(context)));
    }
    private static JSONObject base(String name,long elapsed)throws Exception {
        return new JSONObject().put("event",name).put("elapsed_ms",elapsed).put("wall_time_ms",System.currentTimeMillis())
                .put("segment",SEGMENT).put("boot_key",boot).put("model",Build.MODEL).put("fingerprint",Build.FINGERPRINT);
    }
    static JSONObject snapshot(Context context)throws Exception {
        long begin=SystemClock.elapsedRealtime();
        JSONObject frame=new JSONObject().put("read_start_ms",begin).put("uid",android.os.Process.myUid())
                .put("pid",android.os.Process.myPid()).put("process",processName()).put("service_triggered",triggered.get());
        try {
            Class<?> clazz=Class.forName("android.os.SystemProperties");
            Method get=clazz.getMethod("get",String.class,String.class);
            for(String key:new String[]{"init.svc.bootanim","service.bootanim.exit","sys.boot_completed"})frame.put(key,get.invoke(null,key,""));
        }catch(ReflectiveOperationException|RuntimeException error){frame.put("property_error",error.getClass().getSimpleName());}
        frame.put("read_end_ms",SystemClock.elapsedRealtime());
        try {UserManager users=context.getSystemService(UserManager.class);frame.put("user_unlocked",users==null?JSONObject.NULL:users.isUserUnlocked());}
        catch(RuntimeException error){frame.put("user_unlocked",JSONObject.NULL);}
        return frame;
    }
    static String processName(){try{return read(new File("/proc/self/cmdline")).split("\u0000",2)[0];}catch(Exception e){return "unknown";}}
    private static void poll(Context context) {
        try {
            long now=SystemClock.elapsedRealtime();
            if(now-start>=90_000){sampler.cancel(false);write(base("sampling_finished",now).put("ipsec_in_flight",busy.get()));return;}
            JSONObject frame=snapshot(context);
            String state=frame.optString("init.svc.bootanim")+"/"+frame.optString("service.bootanim.exit")+"/"+frame.optString("sys.boot_completed")+"/"+frame.opt("user_unlocked");
            if(!state.equals(lastState)||now-lastWrite>=1000){write(base("boot_snapshot",now).put("snapshot",frame));lastState=state;lastWrite=now;}
            if(!ready&&attempts<25&&now>=nextCheck&&busy.compareAndSet(false,true)) {
                nextCheck=now+(now-start<5000?250:1000);
                int attempt=++attempts;
                write(base("ipsec_started",now).put("attempt",attempt));
                checks.execute(()->ipsec(context,attempt));
            }
        }catch(Throwable error){Log.e(TAG,"Sample failed",error);}
    }
    private static void ipsec(Context context,int attempt) {
        IpSecManager.UdpEncapsulationSocket socket=null;
        IpSecManager.SecurityParameterIndex spi=null;
        IpSecTransform transform=null;
        long begin=SystemClock.elapsedRealtime();
        JSONObject result=new JSONObject();
        try {
            result.put("ready",false).put("started_ms",begin);
            IpSecManager manager=context.getSystemService(IpSecManager.class);
            if(manager==null)throw new IllegalStateException("IPsec service is not published yet");
            socket=manager.openUdpEncapsulationSocket();
            InetAddress address=InetAddress.getByName("127.0.0.1");
            spi=manager.allocateSecurityParameterIndex(address);
            byte[] aes=new byte[32],hmac=new byte[32];SecureRandom random=new SecureRandom();random.nextBytes(aes);random.nextBytes(hmac);
            int port;try(DatagramSocket sender=new DatagramSocket()){port=sender.getLocalPort();}
            transform=new IpSecTransform.Builder(context).setEncryption(new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC,aes))
                    .setAuthentication(new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256,hmac,128)).setIpv4Encapsulation(socket,port)
                    .buildTransportModeTransform(address,spi);
            result.put("ready",true).put("ready_ms",SystemClock.elapsedRealtime()).put("snapshot_when_ready",snapshot(context));
        }catch(Throwable error){try{result.put("error",error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()).substring(0,Math.min(500,String.valueOf(error.getMessage()).length())));}catch(Exception ignored){}}
        finally {
            if(transform!=null)try{transform.close();}catch(Exception ignored){}
            if(spi!=null)try{spi.close();}catch(Exception ignored){}
            if(socket!=null)try{socket.close();}catch(Exception ignored){}
        }
        timeline.execute(()->{
            try{result.put("duration_ms",SystemClock.elapsedRealtime()-begin);ready=result.optBoolean("ready");write(base("ipsec_result",SystemClock.elapsedRealtime()).put("attempt",attempt).put("details",result));}
            catch(Throwable error){Log.e(TAG,"Result failed",error);}finally{busy.set(false);}
        });
    }
    private static void write(JSONObject record)throws Exception {
        if(file.length()>=MAX_BYTES)return;
        byte[] bytes=(record+"\n").getBytes(StandardCharsets.UTF_8);
        if(file.length()+bytes.length>MAX_BYTES)return;
        try(FileOutputStream out=new FileOutputStream(file,true)){out.write(bytes);}
        Log.i(TAG,record.optString("event")+" "+record.optLong("elapsed_ms"));
    }
    static String report()throws Exception {
        String current=bootKey();File dir=new File(HelperProtocol.DIRECTORY,"observations");
        File chosen=current==null?null:new File(dir,"boot-"+current+".jsonl");
        if(chosen==null||!chosen.isFile()){
            File[] all=dir.listFiles((d,n)->n.matches("boot-[a-z0-9-]+\\.jsonl"));
            if(all==null||all.length==0)return HelperEvidence.report("",current);
            java.util.Arrays.sort(all,java.util.Comparator.comparingLong(File::lastModified).reversed());chosen=all[0];
        }
        if(chosen.length()>MAX_BYTES)throw new IOException("Diagnostic file exceeds limit");
        return HelperEvidence.report(read(chosen),current);
    }
    static String read(File f)throws IOException {try(FileInputStream in=new FileInputStream(f)){byte[] b=in.readNBytes(MAX_BYTES+1);if(b.length>MAX_BYTES)throw new IOException("File too large");return new String(b,StandardCharsets.UTF_8);}}
}
