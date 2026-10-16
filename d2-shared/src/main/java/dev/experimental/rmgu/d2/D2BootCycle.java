package dev.experimental.rmgu.d2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A first verified observation may release root while the bounded vault rechecks continue. */
public final class D2BootCycle {
    private final List<Listener> listeners = new ArrayList<>();
    private boolean verified, finished;

    private static final class Listener {
        final Runnable ready, complete;
        final AtomicBoolean readySent = new AtomicBoolean(), completeSent = new AtomicBoolean();
        Listener(Runnable ready, Runnable complete) { this.ready = ready; this.complete = complete; }
        void ready() { run(ready, readySent); }
        void complete() { run(complete, completeSent); }
        private static void run(Runnable callback, AtomicBoolean sent) {
            if (sent.compareAndSet(false, true)) {
                try { callback.run(); }
                catch (RuntimeException ignored) { /* A lost client must not cancel vault checks. */ }
            }
        }
    }

    public void subscribe(Runnable ready, Runnable complete) {
        Listener listener = new Listener(ready, complete);
        boolean notifyReady, notifyComplete;
        synchronized (this) {
            if (!finished) listeners.add(listener);
            notifyReady = verified;
            notifyComplete = finished;
        }
        if (notifyReady) listener.ready();
        if (notifyComplete) listener.complete();
    }

    public void verified() {
        List<Listener> current;
        synchronized (this) {
            if (verified || finished) return;
            verified = true;
            current = new ArrayList<>(listeners);
        }
        for (Listener listener : current) listener.ready();
    }

    public void finish() {
        List<Listener> current;
        synchronized (this) {
            if (finished) return;
            finished = true;
            current = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Listener listener : current) listener.complete();
    }
}
