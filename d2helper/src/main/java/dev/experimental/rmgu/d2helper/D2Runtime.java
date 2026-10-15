package dev.experimental.rmgu.d2helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import dev.experimental.rmgu.d2.VaultOperation;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded worker, no root run, permanent poller, alarm, or main-thread vault call. */
final class D2Runtime {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicBoolean AGAIN = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean timedOut;
    private static final long[] ATTEMPTS = {0, 500, 2000, 6000};
    static boolean busy() { return BUSY.get(); }
    static boolean timedOut() { return timedOut; }

    static void request(Context context, String event, boolean apply, Runnable complete) {
        Context stored = context.createDeviceProtectedStorageContext();
        if (!BUSY.compareAndSet(false, true)) {
            if (apply && D2Store.enabled(stored)) AGAIN.set(true);
            complete.run();
            return;
        }
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean expired = new AtomicBoolean();
        timedOut = false;
        Runnable finish = () -> { if (finished.compareAndSet(false, true)) complete.run(); };
        long start = SystemClock.elapsedRealtime();
        Runnable watchdog = () -> { expired.set(true); timedOut = true; finish.run(); };
        MAIN.postDelayed(watchdog, 8000);
        Thread worker = new Thread(() -> {
            try {
                for (long when : ATTEMPTS) {
                    long wait = start + when - SystemClock.elapsedRealtime();
                    if (wait > 0) Thread.sleep(wait);
                    if (expired.get() || SystemClock.elapsedRealtime() - start >= 7500) break;
                    if (apply && !D2Store.enabled(stored)) break;
                    VaultOperation.Result result;
                    try {
                        SamsungVault vault = new SamsungVault();
                        result = apply ? VaultOperation.apply(vault,
                                () -> !expired.get() && SystemClock.elapsedRealtime() - start < 7500 && D2Store.enabled(stored))
                                : VaultOperation.read(vault);
                    } catch (VaultOperation.Unsupported error) {
                        result = VaultOperation.read(new Unavailable(error));
                    } catch (Exception error) {
                        result = VaultOperation.read(new Unavailable(error));
                    }
                    D2Store.record(stored, event, result);
                    Log.i("RMGUD2", event + ": " + result.state + " - " + result.detail);
                    if (!apply || result.state == VaultOperation.State.UNSUPPORTED || result.state == VaultOperation.State.FAILED) {
                        AGAIN.set(false);
                        break;
                    }
                    // Successful boot writes are observed again: Samsung can clear AT after our first read.
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                timedOut = true;
            } catch (Throwable error) {
                timedOut = true;
                Log.w("RMGUD2", "D2 worker failed: " + error.getClass().getSimpleName());
            } finally {
                MAIN.removeCallbacks(watchdog);
                BUSY.set(false);
                finish.run();
                if (AGAIN.getAndSet(false) && !timedOut && D2Store.enabled(stored)) request(stored, "recheck", true, () -> {});
            }
        }, "RMGU-D2");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException error) {
            MAIN.removeCallbacks(watchdog);
            BUSY.set(false);
            timedOut = true;
            finish.run();
            throw error;
        }
    }
    private static final class Unavailable implements VaultOperation.Access {
        private final Exception error;
        Unavailable(Exception error) { this.error = error; }
        @Override public byte[] read() throws Exception { throw error; }
        @Override public int write(byte[] ignored) { throw new AssertionError("Read-only failure"); }
    }
}
