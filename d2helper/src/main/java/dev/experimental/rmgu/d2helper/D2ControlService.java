package dev.experimental.rmgu.d2helper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import dev.experimental.rmgu.d2.D2ControlPolicy;
import dev.experimental.rmgu.d2.D2Protocol;
import dev.experimental.rmgu.d2.KernelIdentity;

/** Explicit bound-only control: sideloaded system-process providers are skipped at boot. */
public final class D2ControlService extends Service {
    private HandlerThread worker;
    private Messenger endpoint;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("RMGU-D2-control");
        worker.start();
        endpoint = new Messenger(new Handler(worker.getLooper(), this::receive));
    }
    @Override public IBinder onBind(Intent ignored) { return endpoint.getBinder(); }
    @Override public void onDestroy() {
        worker.quitSafely();
        super.onDestroy();
    }

    static void authorize(Context context, int caller) {
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.getApplicationInfo(D2Protocol.CONTROLLER, 0).uid != caller ||
                    pm.checkSignatures(D2Protocol.CONTROLLER, D2Protocol.HELPER) != PackageManager.SIGNATURE_MATCH ||
                    context.checkPermission(D2Protocol.PERMISSION, -1, caller) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Only the matching RMGU controller is accepted");
            }
        } catch (PackageManager.NameNotFoundException error) { throw new SecurityException("RMGU is absent"); }
        if (Process.myUid() != 1000) throw new SecurityException("Companion is not installed with system UID");
    }

    private boolean receive(Message request) {
        // Messenger supplies the sender UID. Binder.getCallingUid() on this worker would be our own UID.
        try { authorize(this, request.sendingUid); }
        catch (SecurityException error) { Log.w("RMGUD2", "Control request refused", error); return true; }
        if (request.replyTo == null) return true;
        Bundle reply;
        try {
            if (request.what != D2Protocol.REQUEST) throw new IllegalArgumentException("Unknown D2 message");
            Bundle data = request.getData();
            String method = data.getString("method");
            Object value = data.get("enabled");
            if (data.containsKey("enabled") && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid switch value");
            }
            Boolean enabled = (Boolean) value;
            D2ControlPolicy.validate(data.getInt("api"), method, enabled,
                    data.getString("source"), data.getInt("version"), BuildConfig.SOURCE_SHA256,
                    BuildConfig.VERSION_CODE, data.getLong("deadline_ms"), SystemClock.elapsedRealtime());
            if ("configure".equals(method)) {
                try { D2Store.setEnabled(this, enabled); }
                catch (java.io.IOException error) {
                    throw new IllegalStateException("D2 switch was not saved: " + error.getMessage(), error);
                }
                if (enabled) D2Runtime.request(this, "enabled", true, () -> {});
            } else if ("configure_root".equals(method)) {
                EarlyRootBoot.configure(this, enabled);
            } else if ("refresh".equals(method)) {
                D2Runtime.request(this, "status", false, () -> {});
            }
            reply = D2Store.snapshot(this);
            // The regular controller app may not read these proc files. The authenticated
            // system helper reports a bounded, kernel-owned value without changing anything.
            reply.putString("kernel_release", KernelIdentity.read());
            reply.putBoolean("early_root_enabled", EarlyRootBoot.enabled());
            reply.putBoolean("busy", D2Runtime.busy());
            reply.putBoolean("timed_out", D2Runtime.timedOut());
            if (D2Runtime.timedOut()) reply.putBoolean("verified", false);
        } catch (Exception error) {
            reply = new Bundle();
            reply.putString("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            Log.w("RMGUD2", "D2 control failed", error);
        }
        reply.putInt("api", D2Protocol.API);
        reply.putInt("uid", Process.myUid());
        reply.putInt("version", BuildConfig.VERSION_CODE);
        reply.putString("source", BuildConfig.SOURCE_SHA256);
        Message response = Message.obtain();
        response.what = D2Protocol.RESPONSE;
        response.arg1 = request.arg1;
        response.setData(reply);
        try { request.replyTo.send(response); }
        catch (RemoteException error) { Log.w("RMGUD2", "Controller left before the D2 reply", error); }
        return true;
    }
}
