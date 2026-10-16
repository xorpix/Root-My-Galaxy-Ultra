package dev.busung.s25uroot;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A short saved-grant probe: only a completed command printing root's numeric UID qualifies. */
final class RootReadyProbe {
    static boolean check(long timeoutMillis) {
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
