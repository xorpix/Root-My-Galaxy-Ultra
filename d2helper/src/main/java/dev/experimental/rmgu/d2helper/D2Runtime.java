package dev.experimental.rmgu.d2helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import dev.experimental.rmgu.d2.VaultOperation;
import dev.experimental.rmgu.d2.D2BootCycle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded worker, no root run, permanent poller, alarm, or main-thread vault call. */
final class D2Runtime {
    private static final AtomicReference<D2BootCycle> ACTIVE = new AtomicReference<>();
    private static final AtomicBoolean AGAIN = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean timedOut;
    private static final long[] ATTEMPTS = {0, 500, 2000, 6000};
    static boolean busy() { return ACTIVE.get() != null; }
    static boolean timedOut() { return timedOut; }

    static void request(Context context, String event, boolean apply, Runnable complete) {
        request(context, event, apply, () -> {}, complete);
    }

    static void request(Context context, String event, boolean apply, Runnable verified, Runnable complete) {
        Context stored = context.createDeviceProtectedStorageContext();
        D2BootCycle cycle = new D2BootCycle();
        while (true) {
            D2BootCycle current = ACTIVE.get();
            if (current != null) {
                if (apply && D2Store.enabled(stored)) AGAIN.set(true);
                current.subscribe(verified, complete);
                return;
            }
            if (ACTIVE.compareAndSet(null, cycle)) break;
        }
        cycle.subscribe(verified, complete);
        AtomicBoolean expired = new AtomicBoolean();
        timedOut = false;
        long start = SystemClock.elapsedRealtime();
        Runnable watchdog = () -> { expired.set(true); timedOut = true; cycle.finish(); };
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
                    if (result.verified()) cycle.verified();
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
                ACTIVE.compareAndSet(cycle, null);
                cycle.finish();
                if (AGAIN.getAndSet(false) && !timedOut && D2Store.enabled(stored)) request(stored, "recheck", true, () -> {});
            }
        }, "RMGU-D2");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException error) {
            MAIN.removeCallbacks(watchdog);
            ACTIVE.compareAndSet(cycle, null);
            timedOut = true;
            cycle.finish();
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
