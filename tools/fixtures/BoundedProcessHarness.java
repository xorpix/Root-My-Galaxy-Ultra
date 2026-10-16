package dev.busung.s25uroot;

import java.util.concurrent.TimeUnit;

/** Plain-JDK counterpart of the process tests, runnable without the Android/Gradle toolchain. */
public final class BoundedProcessHarness {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("timing")) {
            checkTiming("before_unlock", "android.intent.action.LOCKED_BOOT_COMPLETED", false, false, true);
            checkTiming("after_unlock", "android.intent.action.BOOT_COMPLETED", false, false, true);
            checkTiming("after_unlock", "android.intent.action.LOCKED_BOOT_COMPLETED", true, false, true);
            checkTiming("after_unlock", "android.intent.action.LOCKED_BOOT_COMPLETED", false, true, true);
            checkTiming("after_unlock", "android.intent.action.LOCKED_BOOT_COMPLETED", false, false, false);
            checkTiming("unknown", "unknown", false, false, true);
            checkTiming("unknown", "android.intent.action.LOCKED_BOOT_COMPLETED", null, false, true);
            checkTiming("unknown", "android.intent.action.LOCKED_BOOT_COMPLETED", false, null, true);
            checkTiming("unknown", "android.intent.action.LOCKED_BOOT_COMPLETED", false, false, null);
            System.out.println("timing: PASS (9 cases)");
            return;
        }
        String script;
        long timeout = 2000;
        switch (mode) {
            case "silent": script = "exec sleep 10"; timeout = 100; break;
            case "partial": script = "printf 'uid=0(root)\\n'; exec sleep 10"; timeout = 100; break;
            case "root": script = "printf 'uid=0(root) gid=0(root)\\n'"; break;
            case "denied": script = "printf 'uid=0(root)\\n'; exit 1"; break;
            case "flood": script = "i=0; while [ \"$i\" -lt 12000 ]; do printf '0123456789abcdef\\n'; i=$((i+1)); done"; timeout = 5000; break;
            default: throw new IllegalArgumentException(mode);
        }
        Process process = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
        long start = System.nanoTime();
        try {
            BoundedProcess.Result result = BoundedProcess.read(process, timeout);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (mode.equals("silent") || mode.equals("partial")) {
                if (result != null || elapsed >= 2000 || !process.waitFor(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Stalled child escaped its deadline: " + elapsed);
                }
            } else {
                if (result == null) throw new AssertionError("Completed child timed out");
                if (mode.equals("root") && (result.exitCode != 0 || !result.output.contains("uid=0"))) throw new AssertionError("Root reply lost");
                if (mode.equals("denied") && result.exitCode != 1) throw new AssertionError("Denied command counted as success");
                if (mode.equals("flood") && (result.exitCode != 0 || result.output.length() != 203999)) throw new AssertionError("Output was not drained completely");
            }
            System.out.println(mode + ": PASS (" + elapsed + "ms)");
        } finally { process.destroyForcibly(); }
    }

    private static void checkTiming(String expected, String event, Boolean helperUnlocked, Boolean unlocked, Boolean locked) {
        if (!expected.equals(EarlyRootTiming.classify(event, helperUnlocked, unlocked, locked))) {
            throw new AssertionError("Incorrect timing classification for " + event);
        }
    }
}
