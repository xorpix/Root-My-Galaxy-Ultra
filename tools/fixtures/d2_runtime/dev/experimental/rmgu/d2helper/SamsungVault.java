package dev.experimental.rmgu.d2helper;
import dev.experimental.rmgu.d2.VaultOperation;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
final class SamsungVault implements VaultOperation.Access {
    static long start = android.os.SystemClock.elapsedRealtime(), unavailableUntil;
    static boolean unavailable;
    static final CountDownLatch firstEntered = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
    static final AtomicBoolean first = new AtomicBoolean(true);
    static boolean block;
    SamsungVault() throws VaultOperation.Unsupported {}
    @Override public byte[] read() throws Exception {
        if (block && first.compareAndSet(true, false)) { firstEntered.countDown(); releaseFirst.await(); }
        if (unavailable || android.os.SystemClock.elapsedRealtime() - start < unavailableUntil) throw new IOException("Vault not ready");
        byte[] bytes = new byte[32]; bytes[0] = 1; bytes[2] = 1; return bytes;
    }
    @Override public int write(byte[] bytes) { throw new AssertionError("Already-set test record must not be written"); }
}
