package dev.busung.s25uroot;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** A short saved-grant probe, with an optional already-completed native success marker. */
final class RootReadyProbe {
    /** The marker is the same proof the runner requires after native cleanup. */
    static boolean check(long timeoutMillis, BooleanSupplier nativeCompleted) {
        try { if (nativeCompleted.getAsBoolean()) return true; }
        catch (SecurityException ignored) { /* A denied marker read still requires a completed root command. */ }
        return check(timeoutMillis);
    }

    static boolean check(long timeoutMillis) {
        if (timeoutMillis <= 0) return false;
        long start = System.nanoTime();
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "/system/bin/id -u").redirectErrorStream(true).start();
            long remaining = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return remaining > 0 && read(process, remaining);
        } catch (Exception ignored) { return false; }
        finally { if (process != null) close(process); }
    }

    static boolean read(Process process, long timeoutMillis) {
        try {
            if (timeoutMillis <= 0 || !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return false;
            // Never read to EOF: a descendant may retain stdout after the su client exits.
            InputStream input = process.getInputStream();
            int available = input.available();
            if (available <= 0 || available > 32) return false;
            byte[] bytes = new byte[available];
            int count = input.read(bytes);
            return count == available && new String(bytes, StandardCharsets.UTF_8).trim().equals("0");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) { return false; }
        finally { close(process); }
    }

    private static void close(Process process) {
        process.destroyForcibly();
        try { process.getInputStream().close(); } catch (Exception ignored) {}
        try { process.getOutputStream().close(); } catch (Exception ignored) {}
        try { process.getErrorStream().close(); } catch (Exception ignored) {}
    }
}
