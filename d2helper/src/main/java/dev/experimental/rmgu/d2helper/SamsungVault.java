package dev.experimental.rmgu.d2helper;

import android.os.Build;
import android.os.Process;
import dev.experimental.rmgu.d2.VaultOperation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The only privileged operation offered by this companion: fixed DMC record 1. */
final class SamsungVault implements VaultOperation.Access {
    private final Object instance;
    private final Method read, write;

    SamsungVault() throws Exception {
        if (Process.myUid() != 1000 || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            throw new VaultOperation.Unsupported("Requires a Samsung device and the system-UID helper");
        }
        Class<?> manager;
        try { manager = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager"); }
        catch (ClassNotFoundException error) { throw new VaultOperation.Unsupported("Samsung VaultKeeper is absent"); }
        instance = manager.getMethod("getInstance", String.class).invoke(null, "DMC");
        if (instance == null) throw new IllegalStateException("DMC service is not ready");
        read = manager.getMethod("read", int.class);
        write = manager.getMethod("write", int.class, byte[].class);
    }
    private Object invoke(Method method, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof SecurityException) throw new VaultOperation.Unsupported("Samsung refused DMC access");
            throw new IllegalStateException("DMC service call failed");
        }
    }
    @Override public byte[] read() throws Exception {
        Object result = invoke(read, 1);
        if (!(result instanceof byte[])) throw new IllegalStateException("DMC read is not ready");
        return ((byte[]) result).clone();
    }
    @Override public int write(byte[] bytes) throws Exception {
        Object result = invoke(write, 1, bytes);
        return result instanceof Integer ? (Integer) result : -1;
    }
}
