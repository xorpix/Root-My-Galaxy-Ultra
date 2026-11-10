package dev.busung.s25uroot

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecoveryCleanupTest {
    private fun exercise(exitCode: Int, consume: Boolean, waitInBody: Boolean, timeoutSeconds: Long = 8) {
        val directory = Files.createTempDirectory("recovery-cleanup-").toFile()
        val script = File(directory, "helper.sh")
        val accepted = File(directory, "accepted")
        val log = File(directory, "repair's log")
        val daemonLog = File(directory, "daemon.log")
        val unrelated = File(directory, "live-payload")
        var process: Process? = null
        try {
            log.writeText("diagnostic output")
            daemonLog.writeText("daemon output")
            unrelated.writeText("keep")
            script.writeText("""
                ACCEPTED=${shellQuote(accepted.path)}
                ${RootRecovery.handoffConsumedSnippet()}
                ${RootRecovery.cleanupFilesSnippet(log.path, listOf(daemonLog.path))}
                printf '%s\n' 'error:test-detail' > "${'$'}ACCEPTED"
                ${if (waitInBody) "rmg_handoff_consumed || true" else ":"}
                exit $exitCode
            """.trimIndent())
            val child = ProcessBuilder("/bin/sh", script.path).redirectErrorStream(true).start()
            process = child
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!accepted.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("child did not publish its refusal", accepted.exists())
            assertEquals("error:test-detail", accepted.readText().trim())
            assertTrue("log removed before acknowledgement", log.exists())
            if (consume) assertTrue(accepted.delete())
            assertTrue("cleanup exceeded the bounded handoff wait", child.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            assertEquals(child.inputStream.bufferedReader().readText(), exitCode, child.exitValue())
            listOf(script, accepted, log, daemonLog).forEach { assertFalse(it.path, it.exists()) }
            assertEquals("keep", unrelated.readText())
        } finally {
            process?.destroyForcibly()
            directory.deleteRecursively()
        }
    }

    @Test fun completedChildRemovesOnlyItsOwnFiles() = exercise(0, consume = true, waitInBody = true)
    @Test fun refusalRemainsReadableAndPreservesExitCode() = exercise(7, consume = true, waitInBody = false)
    @Test fun childWhoseCallerDiedStillCleansUp() = exercise(7, consume = false, waitInBody = false)
    @Test fun consumedWaitIsNotRepeatedDuringCleanup() = exercise(0, consume = false, waitInBody = true, timeoutSeconds = 5)

    @Test fun everyRecoveryHelperInstallsCleanup() {
        listOf(
            RootRecovery.restartZygoteScript("boot", "/tmp/accepted", "/tmp/report"),
            RootRecovery.softRebootScript("boot", "/tmp/accepted"),
            RootRecovery.reloadModulesScript("boot", "/tmp/accepted"),
            RootRecovery.rebootScript("boot", "/tmp/accepted"),
        ).forEach { script ->
            assertTrue(script.contains("trap rmg_cleanup_files EXIT"))
            assertTrue(script.indexOf("rmg_cleanup_files()") < script.indexOf(" || reject_handoff"))
            assertFalse(script.contains("rm -rf -- /data/local/tmp"))
        }
    }
}
