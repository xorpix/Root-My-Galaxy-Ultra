package dev.experimental.rmgu.bootprobe;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class RootWallpaperCommandTest {
    @Test public void shellPreservesSpecialCharactersWithoutExecutingThem() throws Exception {
        String value = "/data/app/a' b/$(printf injected)`printf injected`\nbase.apk";
        Process process = new ProcessBuilder("sh", "-c", "printf '%s' " + RootWallpaperCommand.quote(value))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor());
        assertEquals(value, output);
    }
    @Test public void refusesExternalCodeLocation() {
        try {
            RootWallpaperCommand.forApk("/sdcard/other.apk");
            fail("Only an installed APK can be the helper classpath");
        } catch (IllegalArgumentException expected) { }
    }
    @Test public void refusesNullByte() {
        try {
            RootWallpaperCommand.forApk("/data/app/base.apk\0suffix");
            fail("A shell argument cannot contain a null byte");
        } catch (IllegalArgumentException expected) { }
    }
}
