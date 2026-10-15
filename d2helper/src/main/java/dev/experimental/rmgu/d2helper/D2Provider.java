package dev.experimental.rmgu.d2helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import dev.experimental.rmgu.d2.D2Protocol;

/** No arbitrary shell, file, property, vault, record or byte-offset interface is exported. */
public final class D2Provider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    static void authorize(Context context, int caller) {
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.getApplicationInfo(D2Protocol.CONTROLLER, 0).uid != caller ||
                    pm.checkSignatures(D2Protocol.CONTROLLER, D2Protocol.HELPER) != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Only the matching RMGU controller is accepted");
            }
        } catch (PackageManager.NameNotFoundException error) { throw new SecurityException("RMGU is absent"); }
        if (Process.myUid() != 1000) throw new SecurityException("Companion is not installed with system UID");
    }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        authorize(getContext(), Binder.getCallingUid());
        long identity = Binder.clearCallingIdentity();
        try {
            Context context = getContext();
            if ("configure".equals(method)) {
                if (extras == null || !extras.containsKey("enabled")) throw new IllegalArgumentException("Missing switch value");
                boolean enabled = extras.getBoolean("enabled");
                if (enabled && !D2Store.snapshot(context).getBoolean("activated")) {
                    throw new IllegalStateException("Activate the helper before enabling D2");
                }
                try { D2Store.setEnabled(context, enabled); }
                catch (java.io.IOException error) {
                    throw new IllegalStateException("D2 switch was not saved: " + error.getMessage(), error);
                }
                if (enabled) D2Runtime.request(context, "enabled", true, () -> {});
            } else if ("refresh".equals(method)) {
                D2Runtime.request(context, "status", false, () -> {});
            } else if (!"status".equals(method)) throw new IllegalArgumentException("Unknown D2 operation");
            Bundle reply = D2Store.snapshot(context);
            reply.putInt("api", D2Protocol.API);
            reply.putInt("uid", Process.myUid());
            reply.putInt("version", BuildConfig.VERSION_CODE);
            reply.putString("source", BuildConfig.SOURCE_SHA256);
            reply.putBoolean("busy", D2Runtime.busy());
            reply.putBoolean("timed_out", D2Runtime.timedOut());
            if (D2Runtime.timedOut()) reply.putBoolean("verified", false);
            return reply;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
}
