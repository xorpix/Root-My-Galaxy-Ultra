package dev.busung.s25uroot

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reproduce blocked pipes with real children; a waitFor timeout after read-to-EOF fails these. */
class SuShellProcessTest {
    private fun child(script: String): Process {
        assumeTrue("POSIX shell fixture", File("/bin/sh").canExecute())
        return ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
    }

    @Test fun silentChildCannotBlockTheProbeUntilItEventuallyExits() {
        val process = child("exec sleep 10")
        val start = System.nanoTime()
        try {
            assertNull(SuShell.readProcess(process, 100))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            assertTrue(process.waitFor(1, TimeUnit.SECONDS))
        } finally { process.destroyForcibly() }
    }

    @Test fun printingRootBeforeStallingDoesNotCountAsSuccessfulVerification() {
        val process = child("printf 'uid=0(root)\\n'; exec sleep 10")
        try { assertNull(SuShell.readProcess(process, 100)) }
        finally { process.destroyForcibly() }
    }

    @Test fun aCompletedRootCommandStillReturnsItsOutput() {
        val result = SuShell.readProcess(child("printf 'uid=0(root) gid=0(root)\\n'"), 2000)
        assertNotNull(result)
        assertEquals(0, result!!.exitCode)
        assertTrue(isRootAnswer(result))
    }

    @Test fun deniedCommandsCannotBecomeRootProof() {
        val result = SuShell.readProcess(child("printf 'uid=0(root)\\n'; exit 1"), 2000)
        assertEquals(1, result!!.exitCode)
        assertFalse(isRootAnswer(result))
    }

    @Test fun largeOutputIsDrainedWithoutBlockingOrTruncatingTheChild() {
        val script = "i=0; while [ \"\$i\" -lt 12000 ]; do printf '0123456789abcdef\\n'; i=\$((i + 1)); done"
        val result = SuShell.readProcess(child(script), 5000)
        assertNotNull(result)
        assertEquals(0, result!!.exitCode)
        assertEquals(203999, result.output.length)
    }
}
