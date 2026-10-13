package dev.experimental.rmgu.bootprobe;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** No root commands, boot-animation writes, foreground-service launch, or wake lock. */
final class ProbeRuntime {
    private static final String TAG = "RMGUBootProbe";
    private static final long WINDOW_MS = 90_000;
    private static final int MAX_IPSEC_ATTEMPTS = 20;
    private static final String SEGMENT = UUID.randomUUID().toString();
    private static final ScheduledExecutorService timeline = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "boot-probe-timeline");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService readiness = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "boot-probe-ipsec");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicBoolean realEngine = new AtomicBoolean();
    private static final AtomicBoolean ipsecBusy = new AtomicBoolean();
    private static volatile boolean ipsecReady;
    private static volatile boolean readyWithRealEngine;
    private static volatile boolean finished;
    private static ProbeStore store;
    private static int attempts;
    private static long startMs;
    private static long nextAttempt;
    private static long lastHeartbeat;
    private static String lastState = "";
    private static ScheduledFuture<?> sampler;

    static void event(Context context, String name) { event(context, name, null); }

    static void event(Context context, String name, JSONObject details) {
        long elapsed = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        Context de = context.createDeviceProtectedStorageContext();
        timeline.execute(() -> {
            try {
                initialize(de);
                JSONObject event = base(name, elapsed, wall);
                if (details != null) event.put("details", details);
                write(event);
            } catch (Exception error) { Log.e(TAG, "Record failed", error); }
        });
    }

    static void engine(Context context, boolean preview) {
        if (!preview) realEngine.set(true);
        try { event(context, "wallpaper_engine_created", new JSONObject().put("preview", preview)); }
        catch (Exception error) { Log.e(TAG, "Engine record failed", error); }
    }

    static void start(Context context, String reason) {
        event(context, reason);
        if (!started.compareAndSet(false, true)) return;
        Context de = context.createDeviceProtectedStorageContext();
        timeline.execute(() -> {
            try {
                initialize(de);
                startMs = SystemClock.elapsedRealtime();
                write(base("sampling_started", startMs, System.currentTimeMillis())
                        .put("duration_limit_ms", WINDOW_MS).put("sample_interval_ms", 250));
                sampler = timeline.scheduleWithFixedDelay(() -> poll(de), 0, 250, TimeUnit.MILLISECONDS);
            } catch (Exception error) { Log.e(TAG, "Probe start failed", error); }
        });
    }

    private static void initialize(Context context) throws Exception {
        if (store != null) return;
        store = new ProbeStore(context);
        JSONObject info = base("process_started", SystemClock.elapsedRealtime(), System.currentTimeMillis());
        info.put("model", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT).put("probe_version", "0.1.0")
                .put("storage", "device_protected");
        write(info);
    }

    private static JSONObject base(String name, long elapsed, long wall) throws Exception {
        return new JSONObject().put("event", name).put("elapsed_ms", elapsed)
                .put("wall_time_ms", wall).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("segment", SEGMENT);
    }

    static JSONObject snapshot(Context context) throws Exception {
        long begin = SystemClock.elapsedRealtime();
        String[] props;
        String readError = "";
        try { props = NativeProperties.read(); }
        catch (LinkageError | RuntimeException error) {
            props = new String[]{"", "", ""};
            readError = error.getClass().getSimpleName();
        }
        if (props == null || props.length != 3) props = new String[]{"", "", ""};
        JSONObject result = new JSONObject().put("property_read_start_ms", begin)
                .put("property_read_end_ms", SystemClock.elapsedRealtime())
                .put("init.svc.bootanim", props[0]).put("service.bootanim.exit", props[1])
                .put("sys.boot_completed", props[2])
                .put("classification", BootEvidence.classify(props[0], props[1], props[2]).name())
                .put("real_wallpaper_engine_seen", realEngine.get());
        if (!readError.isEmpty()) result.put("property_error", readError);
        try {
            UserManager manager = context.getSystemService(UserManager.class);
            result.put("user_unlocked", manager == null ? JSONObject.NULL : manager.isUserUnlocked());
        } catch (RuntimeException error) {
            result.put("user_unlocked", JSONObject.NULL).put("user_state_error", error.getClass().getSimpleName());
        }
        return result;
    }

    private static void poll(Context context) {
        try {
            long now = SystemClock.elapsedRealtime();
            JSONObject frame = snapshot(context);
            String state = frame.optString("init.svc.bootanim") + "/" + frame.optString("service.bootanim.exit")
                    + "/" + frame.optString("sys.boot_completed") + "/" + frame.opt("user_unlocked")
                    + "/" + frame.optBoolean("real_wallpaper_engine_seen");
            if (!state.equals(lastState) || now - lastHeartbeat >= 1000) {
                write(base("boot_snapshot", now, System.currentTimeMillis()).put("snapshot", frame));
                lastState = state;
                lastHeartbeat = now;
            }
            if (now - startMs >= WINDOW_MS) {
                finished = true;
                write(base("sampling_finished", now, System.currentTimeMillis())
                        .put("ipsec_check_still_in_flight", ipsecBusy.get()));
                if (sampler != null) sampler.cancel(false);
                return;
            }
            if ((!ipsecReady || (realEngine.get() && !readyWithRealEngine))
                    && attempts < MAX_IPSEC_ATTEMPTS && now >= nextAttempt
                    && ipsecBusy.compareAndSet(false, true)) {
                int attempt = ++attempts;
                nextAttempt = now + 2000;
                write(base("ipsec_check_started", now, System.currentTimeMillis()).put("attempt", attempt));
                readiness.execute(() -> checkIpsec(context, attempt));
            }
        } catch (Exception error) { Log.e(TAG, "Sampling failed", error); }
    }

    private static void checkIpsec(Context context, int attempt) {
        try {
            JSONObject result = IpsecReadiness.check(context);
            ipsecReady = result.optBoolean("ready");
            JSONObject frame = result.optJSONObject("snapshot_when_ready");
            if (ipsecReady && frame != null && frame.optBoolean("real_wallpaper_engine_seen"))
                readyWithRealEngine = true;
            long now = SystemClock.elapsedRealtime();
            timeline.execute(() -> {
                try {
                    write(base("ipsec_check_result", now, System.currentTimeMillis())
                            .put("attempt", attempt).put("after_sampling_deadline", finished)
                            .put("details", result));
                } catch (Exception error) { Log.e(TAG, "Readiness record failed", error); }
            });
        } catch (Exception error) { event(context, "ipsec_check_error"); }
        finally { ipsecBusy.set(false); }
    }

    private static void write(JSONObject event) throws Exception {
        store.append(event);
        Log.i(TAG, event.optString("event") + " at " + event.optLong("elapsed_ms") + "ms");
    }

    static void readReport(Context context, java.util.function.Consumer<String> callback) {
        timeline.execute(() -> {
            try { callback.accept(ProbeReport.build(ProbeStore.readLatest(context))); }
            catch (Exception error) { callback.accept("Could not read report: " + error.getMessage()); }
        });
    }

    static void afterQueuedEvents(Runnable callback) { timeline.execute(callback); }
}
