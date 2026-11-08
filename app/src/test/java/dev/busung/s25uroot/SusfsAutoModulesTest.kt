package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Test

class SusfsAutoModulesTest {
    private fun core(missing: Set<String> = emptySet()) = SusfsOutcome(SusfsStatus(
        if (missing.isEmpty()) SusfsState.Active else SusfsState.Partial,
        version = "v2.3.0", variant = "GKI-LKM", features = requiredSusfsFeatures - missing,
        detail = if (missing.isEmpty()) "active" else "Missing: ${missing.joinToString()}",
        abiVerified = true,
    ), "report")
    @Test fun inventoryRequiresACompleteSuccessfulMetadataScan() {
        assertEquals(listOf("alpha", "zram"), susfsStockModuleInventory("stock:zram\nstock:alpha\nstock:zram\ncomplete:3\n", 0))
        for (text in listOf("", "complete:0\n", "stock:zram\n", "stock:zram\ncomplete:2\n",
            "stock:zram\ncomplete:1\ntrailing", "stock:../bad\ncomplete:1\n",
            "stock:bad name\ncomplete:1\n", "stock:\$(id)\ncomplete:1\n",
            "stock:..\ncomplete:1\n", "stock:${"x".repeat(56)}\ncomplete:1\n")) {
            assertNull(text, susfsStockModuleInventory(text, 0))
        }
        assertNull(susfsStockModuleInventory("stock:zram\ncomplete:1\n", 1))
    }

    @Test fun capacityIsExplicitAndDoesNotTruncateTheInventory() {
        val accepted = (0 until 1024).joinToString("\n") { "stock:module$it" } + "\ncomplete:1024\n"
        assertEquals(1024, susfsStockModuleInventory(accepted, 0)!!.size)
        assertNull(susfsStockModuleInventory(accepted.replace("complete:1024", "stock:overflow\ncomplete:1025"), 0))
    }

    @Test fun publishesStockBeforeEnablingThenSeedsAfterCommit() {
        val script = SusfsAutoModules.configureScript(listOf("alpha", "zram"))
        assertTrue(script.indexOf("automatic begin") < script.indexOf("automatic stock alpha,zram"))
        assertTrue(script.indexOf("automatic stock alpha,zram") < script.indexOf("automatic commit"))
        assertTrue(script.indexOf("automatic commit") < script.indexOf("done < /proc/modules"))
        assertTrue(script.contains("\"${'$'}name\""))
        assertFalse(script.contains("\\${'$'}"))
        assertTrue(script.contains("set -eu"))
    }

    @Test fun firmwareInventoryDoesNotTreatDataDirectoriesAsStock() {
        val script = SusfsAutoModules.inventoryScript("/private/cache")
        assertTrue(script.contains("modinfo -F name"))
        assertTrue(script.contains("*.ko.zst"))
        assertTrue(script.contains("*) exit 65"))
        assertFalse(script.contains("/data/adb/modules"))
        assertFalse(script.contains("/data/local/tmp"))
        assertFalse(script.contains("sleep "))
    }

    @Test fun interruptedBootstrapResumesWithoutRescanningFirmware() {
        val core = core()
        val calls = mutableListOf<String>()
        val prefix = "automatic discovery: on stock=200 tracked=1/128 overflow=0 errors=0 seeded="
        val shell: SusfsShell = { command, _ ->
            calls.add(command)
            ShizukuController.ShellResult(0, prefix + if (calls.size == 1) "0\n" else "1\n")
        }
        val result = SusfsAutoModules.attach(core, shell, "/private/cache")
        assertEquals(2, calls.size)
        assertEquals(SusfsAutoModules.seedScript, calls[1])
        assertTrue(result.status.detail.contains("Extra kernel modules are selected automatically."))
    }

    @Test fun failedBootstrapAndRegistryOverflowAreReportedAsIncomplete() {
        val core = core()
        val incomplete = "automatic discovery: on stock=200 tracked=1/128 overflow=0 errors=0 seeded=0\n"
        var calls = 0
        val interrupted: SusfsShell = { _, _ ->
            if (++calls == 1) ShizukuController.ShellResult(0, incomplete) else null
        }
        val result = SusfsAutoModules.attach(core, interrupted, "/private/cache")
        assertEquals(SusfsState.Active, result.status.state)
        assertTrue(result.status.detail.contains("incomplete"))
        val overflow: SusfsShell = { _, _ -> ShizukuController.ShellResult(0,
            incomplete.replace("overflow=0", "overflow=1").replace("seeded=0", "seeded=1")) }
        assertTrue(SusfsAutoModules.attach(core, overflow, "/private/cache").status.detail.contains("incomplete"))
    }

    @Test fun missingOpenRedirectDoesNotBlockIndependentModuleInitialization() {
        val original = core(setOf("CONFIG_KSU_SUSFS_OPEN_REDIRECT"))
        val calls = mutableListOf<String>()
        val shell: SusfsShell = { command, _ ->
            calls.add(command)
            ShizukuController.ShellResult(0, when (calls.size) {
                1 -> "automatic discovery: off stock=0 tracked=0/128 overflow=0 errors=0 seeded=0\n"
                2 -> "stock:zram\ncomplete:1\n"
                else -> "automatic discovery: on stock=1 tracked=1/128 overflow=0 errors=0 seeded=1\n"
            })
        }
        val result = SusfsAutoModules.attach(original, shell, "/private/cache")
        assertEquals(3, calls.size)
        assertTrue(calls.last().contains("automatic commit"))
        assertEquals(SusfsState.Partial, result.status.state)
        assertTrue(result.status.detail.contains("CONFIG_KSU_SUSFS_OPEN_REDIRECT"))
        assertTrue(result.status.detail.contains("Extra kernel modules are selected automatically."))
    }

    @Test fun missingRequiredLayersOrUnverifiedAbiNeverStartASelectionScan() {
        for (required in setOf("CONFIG_KSU_SUSFS_SUS_PATH", "CONFIG_KSU_SUSFS_HIDE_KSU_SUSFS_SYMBOLS")) {
            var calls = 0
            val shell: SusfsShell = { _, _ -> calls++; null }
            SusfsAutoModules.attach(core(setOf(required)), shell, "/private/cache")
            assertEquals(0, calls)
        }
        val original = core(setOf("CONFIG_KSU_SUSFS_OPEN_REDIRECT"))
        var calls = 0
        val shell: SusfsShell = { _, _ -> calls++; null }
        SusfsAutoModules.attach(original.copy(status = original.status.copy(abiVerified = false)), shell, "/private/cache")
        assertEquals(0, calls)
    }
}
