package dev.experimental.rmgu.bootprobe;

import android.os.Process;
import android.system.*;
import dev.experimental.rmgu.bootprobe.shared.HelperProtocol;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed-scope app_process entry point; every operation requires existing UID-0 access. */
public final class PrivilegeSetupMain {
    private static final String STORE="/data/system/packages.xml";
    private static final File SETUP=new File(HelperProtocol.DIRECTORY,"setup");
    private static boolean committed;
    public static void main(String[] args) {
        JSONObject result=new JSONObject();
        try {
            if(Process.myUid()!=0)throw new SecurityException("Existing root access is required");
            if(args.length!=2||!List.of("inspect","prepare","remove").contains(args[0]))throw new IllegalArgumentException("Invalid fixed setup operation");
            String key=args[1];byte[] der=AbxDocument.unhex(key);
            if(!Arrays.equals(der,CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der)).getEncoded()))throw new IOException("Certificate is not canonical X.509 DER");
            byte[] original=readStore();PackageTrust trust=new PackageTrust(original);trust.controllerIndex(key);
            StructStat metadata=Os.lstat(STORE);
            if(!OsConstants.S_ISREG(metadata.st_mode)||metadata.st_uid!=1000||metadata.st_gid!=1000)throw new IOException("Unexpected package-registry ownership/type");
            byte[] label=Os.getxattr(STORE,"security.selinux");
            if(label.length==0)throw new IOException("Package-registry SELinux label is unavailable");
            frameworkParse(original);
            result.put("operation",args[0]).put("packages",trust.packages).put("format","ABX v0")
                    .put("owner",metadata.st_uid+":"+metadata.st_gid).put("mode",Integer.toOctalString(metadata.st_mode&0777))
                    .put("certificate_sha256",digest(der)).put("boot_key",bootKey()).put("trusted_references",trust.ownedCount(key));
            if(args[0].equals("inspect")) {
                File recorded=new File(SETUP,"signer.hex");
                result.put("status","Read-only preflight passed. No system file was changed.")
                        .put("can_prepare",!recorded.exists()||new String(read(recorded,22000),StandardCharsets.US_ASCII).trim().equals(key));
            }else if(args[0].equals("prepare")) {
                if(bootKey().equals("unknown"))throw new IOException("Kernel boot identity is unavailable; preparation was not attempted");
                ensureDirectories();
                File recorded=new File(SETUP,"signer.hex");
                File cleaned=new File(SETUP,"cleanup-boot.txt");
                if(cleaned.exists()&&new String(read(cleaned,100),StandardCharsets.US_ASCII).equals(bootKey()))throw new IOException("Fully reboot after cleanup before preparing another setup");
                if(recorded.exists()&&!new String(read(recorded,22000),StandardCharsets.US_ASCII).trim().equals(key)) {
                    String previous=new String(read(recorded,22000),StandardCharsets.US_ASCII).trim();
                    if(trust.ownedCount(previous)!=0||installedCertificate(trust,HelperProtocol.HELPER)!=null||!cleaned.exists())throw new IOException("An older diagnostic setup must be removed and fully rebooted first");
                    receipt(recorded,key.getBytes(StandardCharsets.US_ASCII));
                }
                if(trust.ownedCount(key)!=0)throw new IOException("This signer already occurs in system history. Inspect the existing setup instead of injecting twice.");
                byte[] patched=trust.add(key);frameworkParse(patched);
                writeSmall(recorded,key.getBytes(StandardCharsets.US_ASCII));
                
                replace(original,patched,metadata,label);
                result.put("status","Helper certificate prepared. Fully reboot before installing the embedded helper.");
            }else {
                File recorded=new File(SETUP,"signer.hex");
                if(!recorded.isFile())throw new IOException("No owned diagnostic signer record exists; no trust cleanup was attempted");
                String owned=new String(read(recorded,22000),StandardCharsets.US_ASCII).trim();AbxDocument.unhex(owned);
                // Remove the package first, then edit a freshly re-read registry. Never restore an old package database.
                String installed=installedCertificate(trust,HelperProtocol.HELPER);
                if(installed!=null&&!installed.equals(owned))throw new IOException("Installed helper has a different signer; refusing automatic removal");
                if(installed!=null) {
                    java.lang.Process child=new ProcessBuilder("/system/bin/pm","uninstall",HelperProtocol.HELPER).redirectErrorStream(true).start();
                    ByteArrayOutputStream output=new ByteArrayOutputStream();
                    Thread drain=new Thread(()->{try{child.getInputStream().transferTo(output);}catch(IOException ignored){}});drain.start();
                    if(!child.waitFor(60,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("Helper uninstall timed out; retry cleanup after checking Package Manager");}
                    drain.join(2000);
                    if(child.exitValue()!=0||!output.toString(StandardCharsets.UTF_8).contains("Success"))throw new IOException("Helper uninstall failed: "+output.toString(StandardCharsets.UTF_8));
                }
                original=readStore();trust=new PackageTrust(original);metadata=Os.lstat(STORE);label=Os.getxattr(STORE,"security.selinux");
                if(!OsConstants.S_ISREG(metadata.st_mode)||metadata.st_uid!=1000||metadata.st_gid!=1000||label.length==0)throw new IOException("Unexpected registry metadata after uninstall; trust cleanup was not attempted");
                int removed=trust.ownedCount(owned);
                if(removed>0){byte[] patched=trust.remove(owned);frameworkParse(patched);replace(original,patched,metadata,label);}
                if(new PackageTrust(readStore()).ownedCount(owned)!=0)throw new IOException("Diagnostic certificate removal was not verified");
                receipt(new File(SETUP,"cleanup-boot.txt"),bootKey().getBytes(StandardCharsets.US_ASCII));
                result.put("removed_references",removed).put("status","Helper removed and diagnostic trust absent on disk. Fully reboot to clear the framework's cached trust.");
                // Retain signer and backups for repeat verification/recovery; do not delete package data indiscriminately.
            }
            result.put("ok",true).put("committed",committed);
            System.out.println("RMGU_SETUP_RESULT "+result);
        }catch(Throwable error){
            try{result.put("ok",false).put("committed",committed).put("error",error.getClass().getSimpleName()+": "+error.getMessage());}catch(Exception ignored){}
            System.out.println("RMGU_SETUP_RESULT "+result);System.exit(1);
        }
    }
    private static String installedCertificate(PackageTrust trust,String packageName)throws Exception {
        for(AbxDocument.Element p:trust.doc.root.children("package"))if(p.text("name").equals(packageName)) {
            List<AbxDocument.Element> s=p.children("sigs");if(s.size()!=1||s.get(0).children("cert").size()!=1)throw new IOException("Unsupported installed helper signature layout");
            AbxDocument.Element c=s.get(0).children("cert").get(0);
            for(AbxDocument.Token t:c.attributes)if(t.name.equals("key"))return t.text().toLowerCase(Locale.ROOT);
            int i=c.attr("index").integer();if(i<0||i>=trust.table.size()||trust.table.get(i)==null)throw new IOException("Unresolved helper signer");return trust.table.get(i);
        }
        return null;
    }
    private static void frameworkParse(byte[] bytes)throws Exception {
        Object parser=Class.forName("android.util.Xml").getMethod("resolvePullParser",InputStream.class).invoke(null,new ByteArrayInputStream(bytes));
        XmlPullParser xml=(XmlPullParser)parser;int packages=0;
        for(int event=xml.getEventType();event!=XmlPullParser.END_DOCUMENT;event=xml.next())if(event==XmlPullParser.START_TAG&&"package".equals(xml.getName()))packages++;
        if(packages<20)throw new IOException("Android's XML parser did not validate the package document");
    }
    private static byte[] readStore()throws Exception {
        StructStat stat=Os.lstat(STORE);if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_size<6||stat.st_size>24*1024*1024)throw new IOException("Invalid package-store file");
        return read(new File(STORE),24*1024*1024);
    }
    private static byte[] read(File file,int limit)throws IOException {try(FileInputStream in=new FileInputStream(file)){byte[] b=in.readNBytes(limit+1);if(b.length>limit)throw new IOException("File exceeds diagnostic limit");return b;}}
    private static void ensureDirectories()throws Exception {
        File parent=new File(HelperProtocol.DIRECTORY);
        if(!parent.exists())Os.mkdir(parent.getPath(),0700);
        StructStat stat=Os.lstat(parent.getPath());if(!OsConstants.S_ISDIR(stat.st_mode)||(stat.st_uid!=0&&stat.st_uid!=1000))throw new IOException("Unexpected diagnostic directory");
        Os.chown(parent.getPath(),1000,1000);Os.chmod(parent.getPath(),0700);
        if(!SETUP.exists())Os.mkdir(SETUP.getPath(),0700);
        stat=Os.lstat(SETUP.getPath());if(!OsConstants.S_ISDIR(stat.st_mode)||stat.st_uid!=0)throw new IOException("Unexpected setup directory ownership");
        Os.chmod(SETUP.getPath(),0700);
    }
    private static void writeSmall(File file,byte[] bytes)throws Exception {
        if(file.exists()){if(!Arrays.equals(read(file,22000),bytes))throw new IOException("Existing setup receipt does not match this build");return;}
        FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_WRONLY|OsConstants.O_NOFOLLOW,0600);
        try(FileOutputStream out=new FileOutputStream(fd)){out.write(bytes);out.getFD().sync();}
        syncDirectory(file.getParentFile());
    }
    private static void receipt(File file,byte[] bytes)throws Exception {
        File temp=new File(SETUP,"receipt-"+UUID.randomUUID()+".tmp");
        writeSmall(temp,bytes);Os.rename(temp.getPath(),file.getPath());syncDirectory(SETUP);
    }
    private static void replace(byte[] original,byte[] patched,StructStat metadata,byte[] label)throws Exception {
        ensureDirectories();String id=UUID.randomUUID().toString();
        File backup=new File(SETUP,"packages-before-"+id+".abx"),stage=new File("/data/system/packages.xml.rmgu-probe-"+id+".new");
        writeNew(backup,original,metadata,label);if(!Arrays.equals(read(backup,24*1024*1024),original))throw new IOException("Backup verification failed");
        writeNew(stage,patched,metadata,label);frameworkParse(read(stage,24*1024*1024));
        try {
            if(new File("/data/system/packages-backup.xml").exists()||new File("/data/system/packages.xml.bak").exists())throw new IOException("Package Manager backup indicates a write in progress; retry after it finishes");
            StructStat current=Os.lstat(STORE);
            if(current.st_ino!=metadata.st_ino||current.st_dev!=metadata.st_dev||!Arrays.equals(readStore(),original))throw new IOException("Package Manager changed the registry during setup; original was not replaced");
            // Stage is already complete, parsed, owned, labelled, and fsynced. No direct truncating writes.
            Os.rename(stage.getPath(),STORE);committed=true;
            syncDirectory(new File("/data/system"));
            verifyMetadata(new File(STORE),metadata,label);
            if(!Arrays.equals(readStore(),patched))throw new IOException("Registry changed after commit; inspect before repeating");
        }finally{if(stage.exists())stage.delete();}
    }
    private static void writeNew(File file,byte[] bytes,StructStat metadata,byte[] label)throws Exception {
        FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_WRONLY|OsConstants.O_NOFOLLOW,0600);
        try(FileOutputStream out=new FileOutputStream(fd)){out.write(bytes);out.getFD().sync();}
        Os.chown(file.getPath(),metadata.st_uid,metadata.st_gid);Os.chmod(file.getPath(),metadata.st_mode&0777);
        Os.setxattr(file.getPath(),"security.selinux",label,0);verifyMetadata(file,metadata,label);
        FileDescriptor ready=Os.open(file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0);
        try{Os.fsync(ready);}finally{Os.close(ready);}
        syncDirectory(file.getParentFile());
    }
    private static void syncDirectory(File directory)throws Exception {
        FileDescriptor fd=Os.open(directory.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0);
        try{if(!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))throw new IOException("Expected a directory for fsync");Os.fsync(fd);}finally{Os.close(fd);}
    }
    private static void verifyMetadata(File file,StructStat original,byte[] label)throws Exception {
        StructStat stat=Os.lstat(file.getPath());
        if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_uid!=original.st_uid||stat.st_gid!=original.st_gid||(stat.st_mode&0777)!=(original.st_mode&0777)||!Arrays.equals(Os.getxattr(file.getPath(),"security.selinux"),label))throw new IOException("Owner/mode/SELinux metadata verification failed");
    }
    private static String digest(byte[] bytes)throws Exception{return AbxDocument.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String bootKey(){try{return "kernel-"+new String(read(new File("/proc/sys/kernel/random/boot_id"),100),StandardCharsets.US_ASCII).trim();}catch(Exception ignored){return "unknown";}}
}
