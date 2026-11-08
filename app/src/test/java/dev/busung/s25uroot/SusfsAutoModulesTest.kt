package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Test

class SusfsAutoModulesTest {
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
        val script = SusfsAutoModules.inventoryScript
        assertTrue(script.contains("modinfo -F name"))
        assertTrue(script.contains("*.ko.zst"))
        assertTrue(script.contains("*) exit 65"))
        assertFalse(script.contains("/data/adb/modules"))
        assertFalse(script.contains("sleep "))
    }
}
