package dev.experimental.rmgu.boothelper;

import android.app.NotificationManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import dev.experimental.rmgu.bootprobe.shared.*;

/** Signature-protected fixed operations; resolving this provider never starts sampling. */
public final class HelperProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private void checkCaller() {
        String[] packages=getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        boolean owner=false;
        if(packages!=null)for(String pkg:packages)if(HelperProtocol.CONTROLLER.equals(pkg))owner=true;
        if(!owner||getContext().getPackageManager().checkSignatures(HelperProtocol.CONTROLLER,HelperProtocol.HELPER)!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Only the matching diagnostic controller may use this provider");
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        checkCaller();
        long identity=Binder.clearCallingIdentity();
        try {
            Bundle result=new Bundle();
            result.putInt("uid",android.os.Process.myUid());result.putString("process",HelperRuntime.processName());
            if(!"system_server".equals(HelperRuntime.processName())||android.os.Process.myUid()!=1000)
                throw new SecurityException("Helper is not hosted in system_server");
            if("activate".equals(method)||"deactivate".equals(method)) {
                boolean enabled="activate".equals(method);
                NotificationManager manager=getContext().getSystemService(NotificationManager.class);
                if(manager==null)throw new IllegalStateException("Notification service is unavailable");
                // Grants/revokes only this helper's provider binding; never changes the DND mode.
                NotificationManager.class.getMethod("setNotificationPolicyAccessGranted",String.class,boolean.class)
                        .invoke(manager,HelperProtocol.HELPER,enabled);
                result.putString("status",enabled?"Startup binding enabled. Fully reboot for the timing test.":"Startup binding disabled.");
            }else if("report".equals(method))result.putString("report",HelperRuntime.report());
            else if("status".equals(method))result.putString("status","System UID and system_server hosting verified.");
            else throw new IllegalArgumentException("Unknown diagnostic operation");
            return result;
        }catch(Exception error){Bundle result=new Bundle();result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());return result;}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    @Override public String getType(Uri uri){return "text/plain";}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
