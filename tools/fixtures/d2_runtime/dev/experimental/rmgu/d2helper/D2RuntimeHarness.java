package dev.experimental.rmgu.d2helper;

import android.content.Context;
import android.os.SystemClock;
import dev.experimental.rmgu.d2.D2BootCycle;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production worker and real vault transactions against a fake Android scheduler/vault. */
public final class D2RuntimeHarness {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("cycle")) { cycle(); return; }
        SamsungVault.start = SystemClock.elapsedRealtime();
        SamsungVault.unavailableUntil = mode.equals("delayed") ? 1500 : 0;
        SamsungVault.unavailable = mode.equals("fallback");
        SamsungVault.block = mode.equals("busy") || mode.equals("watchdog");
        long start = SystemClock.elapsedRealtime();
        AtomicInteger ready = new AtomicInteger(), completed = new AtomicInteger(), joined = new AtomicInteger();
        CountDownLatch firstReady = new CountDownLatch(1), done = new CountDownLatch(1);
        D2Runtime.request(new Context(), "boot", true, () -> { ready.incrementAndGet(); firstReady.countDown(); },
                () -> { completed.incrementAndGet(); done.countDown(); });
        if (mode.equals("busy") || mode.equals("watchdog")) {
            if (!SamsungVault.firstEntered.await(1, TimeUnit.SECONDS)) throw new AssertionError("Worker did not enter vault");
            D2Runtime.request(new Context(), "joined", false, joined::incrementAndGet, () -> {});
            if (ready.get() != 0 || joined.get() != 0) throw new AssertionError("Busy request escaped unverified vault");
            if (mode.equals("busy")) SamsungVault.releaseFirst.countDown();
        }
        if (!mode.equals("fallback") && !mode.equals("watchdog")) {
            if (!firstReady.await(3, TimeUnit.SECONDS)) throw new AssertionError("Early verified handoff missing");
            long early = SystemClock.elapsedRealtime() - start;
            if (completed.get() != 0 || early >= 3000) throw new AssertionError("Root waited for final recheck");
        }
        if (!done.await(9, TimeUnit.SECONDS)) throw new AssertionError("Worker completion missed deadline");
        long elapsed = SystemClock.elapsedRealtime() - start;
        if (mode.equals("watchdog")) {
            if (elapsed < 7500 || !D2Runtime.timedOut() || ready.get() != 0) throw new AssertionError("Watchdog handoff failed");
            SamsungVault.releaseFirst.countDown();
            Thread.sleep(150);
            if (ready.get() != 0 || joined.get() != 0) throw new AssertionError("Late vault reply escaped finished cycle");
        } else {
            if (elapsed < 5800 || D2Store.observations.get() != 4) throw new AssertionError("Not all four Odin checks ran: " + D2Store.observations);
            if (ready.get() != (mode.equals("fallback") ? 0 : 1)) throw new AssertionError("Wrong handoff count");
            if (mode.equals("busy") && joined.get() != 1) throw new AssertionError("Busy subscriber missed verified handoff");
        }
        if (completed.get() != 1) throw new AssertionError("Completion repeated");
        System.out.println("Odin " + mode + ": PASS (" + elapsed + "ms)");
    }

    private static void cycle() throws Exception {
        D2BootCycle cycle = new D2BootCycle();
        AtomicInteger ready = new AtomicInteger(), done = new AtomicInteger();
        cycle.subscribe(() -> { throw new IllegalStateException("Lost client"); }, () -> { throw new IllegalStateException("Lost client"); });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 100; i++) pool.submit(() -> cycle.subscribe(ready::incrementAndGet, done::incrementAndGet));
        pool.submit(cycle::verified); pool.submit(cycle::verified);
        pool.shutdown(); if (!pool.awaitTermination(2, TimeUnit.SECONDS)) throw new AssertionError("Subscriber deadlock");
        cycle.finish(); cycle.finish(); cycle.verified();
        if (ready.get() != 100 || done.get() != 100) throw new AssertionError("Callback duplication/loss");
        D2BootCycle expired = new D2BootCycle();
        expired.finish(); expired.verified();
        expired.subscribe(ready::incrementAndGet, done::incrementAndGet);
        if (ready.get() != 100 || done.get() != 101) throw new AssertionError("Expired cycle reported late verification");
        System.out.println("Concurrent Odin subscribers: PASS");
    }
}
