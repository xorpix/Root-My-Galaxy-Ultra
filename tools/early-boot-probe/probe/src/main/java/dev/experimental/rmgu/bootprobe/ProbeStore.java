package dev.experimental.rmgu.bootprobe;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** All calls are serialized by ProbeRuntime; records stay available before first unlock. */
final class ProbeStore {
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private final File file;
    final String bootKey;

    ProbeStore(Context context) {
        Context de = context.createDeviceProtectedStorageContext();
        String key = detectBootKey(de);
        // Never merge different boots using an estimated wall-clock time.
        if (key == null) key = "unidentified-" + UUID.randomUUID();
        bootKey = key;
        File dir = new File(de.getFilesDir(), "boot-observations");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create diagnostic storage");
        file = new File(dir, "boot-" + key + ".jsonl");
        if (!de.getSharedPreferences("probe-index", Context.MODE_PRIVATE).edit()
                .putString("latest", file.getName()).commit())
            throw new IllegalStateException("Cannot record diagnostic file index");
        File[] old = dir.listFiles((d, name) -> name.endsWith(".jsonl"));
        if (old != null) {
            Arrays.sort(old, Comparator.comparingLong(File::lastModified).reversed());
            int retained = 0;
            for (File entry : old) {
                if (entry.equals(file)) continue;
                if (++retained > 4) entry.delete();
            }
        }
    }

    static String detectBootKey(Context context) {
        Context de = context.createDeviceProtectedStorageContext();
        String key = null;
        try {
            String id = TextFiles.read(new File("/proc/sys/kernel/random/boot_id")).trim();
            if (id.matches("[0-9a-f-]{36}")) key = "kernel-" + id;
        } catch (Exception ignored) { /* Stock Samsung may deny this read. */ }
        if (key == null) {
            try {
                int count = Settings.Global.getInt(de.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
                if (count >= 0) key = "count-" + count;
            } catch (RuntimeException ignored) { /* Keep a distinct unidentified segment. */ }
        }
        return key;
    }

    void append(JSONObject event) throws Exception {
        if (file.length() >= MAX_BYTES) return;
        event.put("boot_key", bootKey);
        byte[] bytes = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
    }

    static String readLatest(Context context) throws Exception {
        Context de = context.createDeviceProtectedStorageContext();
        String name = de.getSharedPreferences("probe-index", Context.MODE_PRIVATE).getString("latest", "");
        if (!name.matches("boot-[a-z0-9-]+\\.jsonl")) return "";
        File file = new File(new File(de.getFilesDir(), "boot-observations"), name);
        if (!file.isFile() || file.length() > MAX_BYTES + 8192) return "";
        return TextFiles.read(file);
    }
}
