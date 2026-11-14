package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootRootOptionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = BootRootOptions(SharedJsonFile(File(temporary.root, "options.json")),
        legacyEnabled = { true }, legacyRetry = { "legacy-boot" })

    @Test fun existingReadersObserveOptOutAndConsumedRetriesWithoutReimportingPreferences() {
        val ui = store()
        val boot = store()
        assertTrue(boot.enabled())
        assertEquals("legacy-boot", boot.retry())
        ui.setEnabled(false)
        assertFalse(boot.enabled())
        assertTrue(boot.consumeRetry("current-boot"))
        assertNull(ui.retry())
        assertFalse(store().enabled())
        assertNull(store().retry())
    }

    @Test fun consumingAnOldRequestPreservesARetryArmedForTheNextBoot() {
        val ui = store()
        val boot = store()
        ui.setRetry("current-boot")
        assertFalse(boot.consumeRetry("current-boot"))
        assertEquals("current-boot", boot.retry())
        assertTrue(boot.consumeRetry("next-boot"))
        assertNull(ui.retry())
    }

    @Test fun damagedOptionsStayOffUntilAnExplicitEditAndDoNotRestoreALegacyRetry() {
        for (invalid in listOf("damaged", "{}", "{\"enabled\":true}")) {
            File(temporary.root, "options.json").writeText(invalid)
            assertFalse(store().enabled())
            assertNull(store().retry())
            store().setEnabled(true)
            assertTrue(store().enabled())
            assertNull(store().retry())
        }
    }
}
