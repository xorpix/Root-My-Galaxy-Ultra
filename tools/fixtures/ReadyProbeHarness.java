package dev.busung.s25uroot;

import java.util.concurrent.TimeUnit;

/** Real process/pipe checks for the exact saved-grant readiness probe used by JNI. */
public final class ReadyProbeHarness {
    public static void main(String[] args) throws Exception {
        String[] scripts = {"printf '0\\n'", "printf '2000\\n'", "printf '0\\n'; exit 1", "exit 0",
                "printf '0\\n'; exec sleep 10", "exec sleep 10", "printf '0 extra\\n'",
                "(sleep 1) & printf '0\\n'; exit 0"};
        boolean[] expected = {true, false, false, false, false, false, false, true};
        for (int i = 0; i < scripts.length; i++) {
            Process process = new ProcessBuilder("/bin/sh", "-c", scripts[i]).redirectErrorStream(true).start();
            long start = System.nanoTime();
            boolean answer = RootReadyProbe.read(process, 120);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (answer != expected[i] || elapsed > 1500) throw new AssertionError("Probe case " + i + ": " + answer + " in " + elapsed + "ms");
            if (!process.waitFor(1, TimeUnit.SECONDS)) throw new AssertionError("Timed-out probe survived");
        }
        System.out.println("Saved-grant readiness: PASS (8 real-process cases)");
    }
}
