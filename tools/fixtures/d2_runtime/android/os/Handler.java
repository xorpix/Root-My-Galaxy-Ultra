package android.os;
import java.util.concurrent.*;
public final class Handler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "fake-android-main"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentMap<Runnable, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    public Handler(Looper ignored) {}
    public void postDelayed(Runnable task, long delay) { pending.put(task, executor.schedule(task, delay, TimeUnit.MILLISECONDS)); }
    public void removeCallbacks(Runnable task) {
        ScheduledFuture<?> future = pending.remove(task); if (future != null) future.cancel(false);
    }
}
