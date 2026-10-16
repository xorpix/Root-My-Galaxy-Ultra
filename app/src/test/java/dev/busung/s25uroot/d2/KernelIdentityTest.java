package dev.busung.s25uroot.d2;

import dev.experimental.rmgu.d2.KernelIdentity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KernelIdentityTest {
    private static final String RELEASE = "6.12.69-android16-6-pb4d3caf-abogkiS948BXXS4BZIG-4k";

    @Test public void onlyFixedReadOnlySourcesAreRequestedAndProcVersionHandlesDeniedSysctl() {
        List<String> paths = new ArrayList<>();
        String release = KernelIdentity.read(path -> {
            paths.add(path);
            if (path.equals("/proc/sys/kernel/osrelease")) throw new IOException("Permission denied");
            assertEquals("/proc/version", path);
            return "Linux\tversion  " + RELEASE + " (builder@host) #1 SMP PREEMPT";
        });
        assertEquals(RELEASE, release);
        assertEquals(Arrays.asList("/proc/sys/kernel/osrelease", "/proc/version"), paths);
    }

    @Test public void missingKernelAccessReturnsNoIdentityRatherThanBreakingOdinStatus() {
        assertNull(KernelIdentity.read(path -> { throw new IOException("Permission denied"); }));
        assertNull(KernelIdentity.read(path -> { throw new SecurityException("Permission denied"); }));
    }

    @Test public void oversizedSourcesAreRejected() {
        assertNull(KernelIdentity.read(path -> "Linux version " + RELEASE + " " + "x".repeat(KernelIdentity.MAX_TEXT)));
    }

    @Test public void sysctlReadStopsBeforeTryingTheSecondSourceWhenItIsUsable() {
        assertEquals(RELEASE, KernelIdentity.read(path -> {
            assertEquals("/proc/sys/kernel/osrelease", path);
            return RELEASE + "\n";
        }));
    }

    @Test public void malformedRepliesCannotBeUsedAsKernelReleases() {
        assertTrue(KernelIdentity.valid(RELEASE));
        for (String value : new String[]{null, "", "default", "Linux version " + RELEASE,
                RELEASE + "\n", RELEASE + "\u0000", "x".repeat(65), "$(id)", "6.12.69;id"}) {
            assertFalse(String.valueOf(value), KernelIdentity.valid(value));
        }
    }
}
