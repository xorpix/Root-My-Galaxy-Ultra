package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EarlyRootConfigTest {
    private fun sample() = EarlyRootConfig(true, true, KernelSuFlavor.Default.id,
        "SM-S948B", "m3q", "S948BXXS4BZIG", DfPort.firmwares.getValue("bzig").kernelRelease,
        "a".repeat(64), 60, false, false, true)

    @Test fun rootOnBootIsAnAuthoritativeMasterSwitch() {
        assertTrue(sample().requested)
        assertFalse(sample().copy(rootOnBoot = false).requested)
        assertFalse(sample().copy(enabled = false).requested)
    }

    @Test fun bootOptionsSurviveRoundTripWithoutCredentialStorage() {
        val config = sample().copy(disableModules = true, wipeModules = true, protectImages = false)
        assertEquals(config, EarlyRootConfig.parse(JSONObject(config.json().toString())))
        assertEquals(DfStageOptions(true, true, false), config.stageOptions)
    }

    @Test fun unknownFormatsBackendsHashesAndDelayAreRejected() {
        for (json in listOf(sample().json().put("api", 99), sample().json().put("flavor", "unknown"),
            sample().json().put("daemonHash", ""), sample().json().put("settleSeconds", -1))) {
            assertThrows(IllegalArgumentException::class.java) { EarlyRootConfig.parse(json) }
        }
    }
}
