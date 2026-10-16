package dev.busung.s25uroot;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.TimeUnit;

/** Drain stdout concurrently; a timeout cannot help if the caller first blocks waiting for EOF. */
final class BoundedProcess {
    private static final long READER_GRACE_MS = 500;

    static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    static Result read(Process process, long timeoutMillis) {
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (Reader input = new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    synchronized (output) {
                        output.append(buffer, 0, count);
                    }
                }
            } catch (Exception ignored) { /* A killed child may close the pipe while it is read. */ }
        }, "rmgu-process-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(READER_GRACE_MS);
                return null;
            }
            reader.join(READER_GRACE_MS);
            synchronized (output) { return new Result(process.exitValue(), output.toString().trim()); }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
