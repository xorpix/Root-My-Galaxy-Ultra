package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KernelBootClaimTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record() = File(temporary.root, "boot-attempt")

    @Test fun coldBootHasOneAttemptAndAUserspaceRestartDoesNotResetIt() {
        val file = record()
        assertFalse(KernelBootClaim(file).claimed("boot-1"))
        assertTrue(KernelBootClaim(file).claim("boot-1"))
        assertTrue(KernelBootClaim(file).claimed("boot-1"))
        assertFalse(KernelBootClaim(file).claim("boot-1"))
        assertTrue(KernelBootClaim(file).claim("boot-2"))
        assertFalse(KernelBootClaim(file).claim("boot-2"))
    }

    @Test fun competingCallersCannotBothClaimTheNativeHandoff() {
        val file = record()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val replies = pool.invokeAll((1..32).map { Callable { KernelBootClaim(file).claim("same-kernel") } })
            assertEquals(1, replies.count { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun damagedAttemptRecordsRefuseInsteadOfAllowingAnotherExploit() {
        val file = record().apply { writeText("boot-1\npartial") }
        assertThrows(IllegalStateException::class.java) { KernelBootClaim(file).claim("boot-1") }
        file.writeText("x".repeat(129))
        assertThrows(IllegalStateException::class.java) { KernelBootClaim(file).claim("boot-2") }
    }

    @Test fun missingBootIdentityCannotConsumeOrBypassAnAttempt() {
        assertThrows(IllegalArgumentException::class.java) { KernelBootClaim(record()).claim("") }
        assertThrows(IllegalArgumentException::class.java) { KernelBootClaim(record()).claim("../boot") }
        assertFalse(record().exists())
    }

    @Test fun independentProcessesShareTheSameAtomicClaim() {
        val file = record()
        val barrier = File(temporary.root, "start")
        val classpath = listOf(KernelBootClaimProcess::class.java, KernelBootClaim::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val children = (1..4).map {
            ProcessBuilder(java, "-cp", classpath, KernelBootClaimProcess::class.java.name,
                file.absolutePath, barrier.absolutePath).redirectErrorStream(true).start()
        }
        try {
            barrier.writeText("go")
            val wins = children.count {
                assertTrue("claim process did not finish", it.waitFor(15, TimeUnit.SECONDS))
                val output = it.inputStream.bufferedReader().readText().trim()
                assertEquals(output, 0, it.exitValue())
                output.lineSequence().last() == "true"
            }
            assertEquals(1, wins)
        } finally { children.forEach { it.destroyForcibly() } }
    }
}

object KernelBootClaimProcess {
    @JvmStatic fun main(args: Array<String>) {
        while (!File(args[1]).exists()) Thread.sleep(5)
        println(KernelBootClaim(File(args[0])).claim("same-kernel"))
    }
}
