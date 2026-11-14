package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class BootRootStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = SharedJsonFile(File(temporary.root, "state.json"))

    @Test fun alreadyRunningReadersSeeNewReceiptsAndDoNotReimportStalePreferences() {
        val legacy = { JSONObject().put("verified", true).put("verifiedBoot", "old-boot") }
        val ui = BootRootState(file(), legacy)
        val worker = BootRootState(file(), legacy)
        assertEquals("old-boot", worker.verifiedBoot())
        ui.markVerified("new-boot")
        assertTrue(worker.hasReceipt())
        assertEquals("new-boot", worker.verifiedBoot())
        assertTrue(worker.claimAttempt("new-boot"))
        assertFalse(ui.claimAttempt("new-boot"))
        assertTrue(ui.attempted("new-boot"))
        assertFalse(ui.attempted("next-boot"))
        assertTrue(ui.claimAttempt("next-boot"))
    }

    @Test fun processContentionDoesNotLoseUpdatesOrClaimTheSameBootTwice() {
        file().write(JSONObject().put("count", 0))
        val children = (1..3).map { process("increment") }
        children.forEach { child ->
            assertTrue(child.waitFor(30, TimeUnit.SECONDS))
            assertEquals(child.inputStream.bufferedReader().readText(), 0, child.exitValue())
        }
        assertEquals(90, file().read()!!.getInt("count"))
        assertEquals(1, file().read()!!.getInt("claims"))
    }

    private fun process(operation: String): Process {
        val classes = listOf(SharedStateProcess::class.java, SharedJsonFile::class.java, JSONObject::class.java,
            kotlin.Unit::class.java)
        val classpath = classes.map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct()
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath.joinToString(File.pathSeparator), SharedStateProcess::class.java.name,
            File(temporary.root, "state.json").absolutePath, operation).redirectErrorStream(true).start()
    }

    @Test fun failedAtomicUpdateRetainsTheLastCompleteRecord() {
        file().write(JSONObject().put("count", 7))
        assertThrows(IllegalStateException::class.java) { file().update { error("Interrupted") } }
        assertEquals(7, file().read()!!.getInt("count"))
    }

    @Test fun fallbackNeverRunsBeforeUnlockOrAfterANativeAttempt() {
        assertTrue(earlyRootMayFallback(true, true, false, true))
        assertFalse(earlyRootMayFallback(false, true, false, true))
        assertFalse(earlyRootMayFallback(true, false, false, true))
        assertFalse(earlyRootMayFallback(true, true, true, true))
        assertFalse(earlyRootMayFallback(true, true, false, false))
    }
}

/** Runs in independent JVMs so the test actually exercises operating-system file locks. */
object SharedStateProcess {
    @JvmStatic fun main(args: Array<String>) {
        val file = SharedJsonFile(File(args[0]))
        repeat(30) { file.update { (it ?: JSONObject()).put("count", (it?.optInt("count") ?: 0) + 1) } }
        if (BootRootState(file).claimAttempt("same-boot")) {
            file.update { val current = requireNotNull(it); current.put("claims", current.optInt("claims") + 1) }
        }
    }
}
