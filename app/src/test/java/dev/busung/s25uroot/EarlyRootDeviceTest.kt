package dev.busung.s25uroot

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class EarlyRootDeviceTest {
    private val genuine = "6.12.69-android16-6-pb4d3caf-abogkiS948BXXS4BZIG-4k"
    private val procVersion = "Linux version $genuine (builder@host) #1 SMP PREEMPT"

    @Test fun originalSysctlIsPreferredWithoutReadingAnotherSource() {
        assertEquals(genuine, EarlyRootDevice.originalKernelRelease { path ->
            assertEquals("/proc/sys/kernel/osrelease", path)
            "$genuine\n"
        })
    }

    @Test fun deniedSysctlFallsBackToTheActualProcVersion() {
        assertEquals(genuine, EarlyRootDevice.originalKernelRelease { path ->
            if (path == "/proc/sys/kernel/osrelease") throw IOException("Permission denied")
            procVersion
        })
    }

    @Test fun emptyOrMalformedSysctlStillTriesTheIndependentSource() {
        for (sysctl in listOf("", "\n", "not a release", "x".repeat(65), "bad\u0000release")) {
            assertEquals(genuine, EarlyRootDevice.originalKernelRelease { path ->
                if (path == "/proc/sys/kernel/osrelease") sysctl else procVersion
            })
        }
    }

    @Test fun procVersionAcceptsOrdinaryWhitespaceBetweenItsFields() {
        assertEquals(genuine, EarlyRootDevice.originalKernelRelease { path ->
            if (path == "/proc/sys/kernel/osrelease") throw IOException("Permission denied")
            "  Linux\tversion   $genuine\t(builder@host) #1 SMP PREEMPT\n"
        })
    }

    @Test fun unreadableProcSourcesDoNotFallBackToAPossiblySpoofedUname() {
        assertThrows(IllegalStateException::class.java) {
            EarlyRootDevice.originalKernelRelease { throw IOException("Permission denied") }
        }
    }

    @Test fun invalidProcVersionIsRejectedInsteadOfSavingAnArbitraryThirdWord() {
        for (proc in listOf("", "other kernel release", "Linux version", "Linux version ${"x".repeat(65)}")) {
            assertThrows(IllegalStateException::class.java) {
                EarlyRootDevice.originalKernelRelease { path ->
                    if (path == "/proc/sys/kernel/osrelease") "" else proc
                }
            }
        }
    }
}
