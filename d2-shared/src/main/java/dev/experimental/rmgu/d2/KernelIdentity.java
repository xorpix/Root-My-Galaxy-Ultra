package dev.experimental.rmgu.d2;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Reads only fixed kernel-owned files. Never falls back to the spoofable uname syscall. */
public final class KernelIdentity {
    private KernelIdentity() {}
    public static final int MAX_TEXT = 4096;
    public interface Reader { String read(String path) throws IOException; }

    public static boolean valid(String release) {
        return release != null && release.length() <= 64 &&
                release.matches("[0-9]+\\.[0-9]+\\.[0-9]+[-+._A-Za-z0-9]*");
    }

    public static String read() { return read(KernelIdentity::readFile); }

    public static String read(Reader reader) {
        try {
            String text = reader.read("/proc/sys/kernel/osrelease");
            if (text != null && text.length() <= MAX_TEXT && valid(text.trim())) return text.trim();
        } catch (IOException | SecurityException ignored) {}
        try {
            String text = reader.read("/proc/version");
            if (text == null || text.length() > MAX_TEXT) return null;
            String[] fields = text.trim().split("\\s+", 4);
            if (fields.length >= 3 && fields[0].equals("Linux") && fields[1].equals("version") && valid(fields[2])) {
                return fields[2];
            }
        } catch (IOException | SecurityException ignored) {}
        return null;
    }

    private static String readFile(String path) throws IOException {
        // The caller supplies no path: read(Reader) selects the two fixed proc files above.
        try (FileInputStream input = new FileInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_TEXT) throw new IOException("Kernel identity exceeds the read limit");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
