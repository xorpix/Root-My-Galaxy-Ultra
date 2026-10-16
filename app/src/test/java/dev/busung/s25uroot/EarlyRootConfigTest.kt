package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
        val config = sample().copy(disableModules = true, wipeModules = true, protectImages = false,
            susfsAutoEnable = true, restartAfterRoot = true)
        assertEquals(config, EarlyRootConfig.parse(JSONObject(config.json().toString())))
        assertEquals(DfStageOptions(true, true, false), config.stageOptions)
    }

    @Test fun oldPoliciesDoNotInventSusfsOrRestartOptIns() {
        val json = sample().json().apply { remove("susfsAutoEnable"); remove("restartAfterRoot") }
        val config = EarlyRootConfig.parse(json)
        assertTrue(config.requested)
        assertFalse(config.susfsAutoEnable)
        assertFalse(config.restartAfterRoot)
    }

    @Test fun unknownFormatsBackendsHashesAndDelayAreRejected() {
        for (json in listOf(sample().json().put("api", 99), sample().json().put("flavor", "unknown"),
            sample().json().put("daemonHash", ""), sample().json().put("settleSeconds", -1))) {
            assertThrows(IllegalArgumentException::class.java) { EarlyRootConfig.parse(json) }
        }
    }

    @Test fun everyBackendRequiresTheVerifiedFirmwareAndExactDaemon() {
        val firmware = DfPort.firmwares.getValue("bzig")
        val snapshot = DeviceSnapshot("samsung", firmware.model, firmware.device, firmware.kernelRelease,
            "#1 SMP PREEMPT", "aarch64", "display", "samsung/m3q", "17", firmware.sdk,
            firmware.abi, firmware.pageSize, firmware.incremental)
        val profiles = DfCatalog.parse(File("src/main/assets/df/catalog.json").readBytes())
        KernelSuFlavor.entries.forEach { flavor ->
            val profile = profiles.single { it.firmware == firmware && it.flavor == flavor }
            val config = sample().copy(flavor = flavor.id, daemonHash = requireNotNull(profile.kernelSu.sha256))
            assertTrue(config.matches(snapshot, profile))
            assertFalse(config.matches(snapshot.copy(incremental = "new-firmware"), profile))
            assertFalse(config.matches(snapshot.copy(kernelRelease = firmware.kernelRelease + "-changed"), profile))
            assertFalse(config.matches(snapshot, profile.copy(kernelSu = profile.kernelSu.copy(sha256 = "0".repeat(64)))))
        }
    }
}
