package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
}
