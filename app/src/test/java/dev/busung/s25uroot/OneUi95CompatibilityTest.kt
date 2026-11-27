package dev.busung.s25uroot

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OneUi95CompatibilityTest {
    private fun catalog() = File("src/main/assets/df/catalog.json").readBytes()

    private fun beta(): DeviceSnapshot {
        val report = JSONObject(File("../docs/device-candidates/SM-S948B-ZZKN.json").readText())
        val observed = report.getJSONObject("observed")
        val requirements = report.getJSONObject("test_requirements")
        assertEquals("9.5", observed.getString("one_ui"))
        return DeviceSnapshot(
            manufacturer = "samsung", model = observed.getString("model"),
            device = observed.getString("device"), kernelRelease = observed.getString("kernel_release"),
            kernelVersionInfo = observed.getString("kernel_version_info"),
            machine = requirements.getString("machine"), buildId = observed.getString("build_id"),
            fingerprint = observed.getString("fingerprint"), androidRelease = observed.getString("android_release"),
            sdk = requirements.getInt("sdk"), abi = requirements.getString("abi"),
            pageSize = requirements.getLong("page_size"), incremental = observed.getString("incremental"),
        )
    }

    @Test fun reportedZzknBetaSelectsEveryBundledBackendAndSharedSusfs() {
        val device = beta()
        assertEquals("S948BXXU6ZZKN", device.incremental)
        assertTrue(DfPort.matches(device))
        assertEquals(SusfsTarget.Supported, susfsTargetFor(device))
        val profiles = DfCatalog.forDevice(catalog(), device)
        KernelSuFlavor.entries.forEach { flavor ->
            val profile = requireNotNull(profiles.resolveFor(device, flavor))
            assertEquals(DfPort.firmwareFor(device), profile.firmware)
            assertEquals(setOf(device.kernelRelease), profile.kernelVersions)
            assertEquals(flavor, profile.flavor)
            assertNull(backendRunRefusal(profile, device, flavor, null, false))
            assertEquals(profile, DfCatalog.requireCurrent(catalog(), device, profile))
        }
    }

    @Test fun everyRegionalBetaUsesItsOwnIdentityAndRejectsPreUpdateProfiles() {
        val reported = beta()
        val models = listOf("SM-S948B", "SM-S948B/DS", "SM-S948U", "SM-S948U1", "SM-S948W",
            "SM-S948N", "SM-S9480", "SM-S9480V", "SM-S948Q", "SM-S948Z", "SM-S948C",
            "SM-S948D", "SM-S948J", "SC-53G", "SCG37")
        models.forEachIndexed { index, model ->
            // Only B uses the screenshot identity; other regional builds are synthetic fixtures.
            val current = if (model == reported.model) reported else reported.copy(model = model,
                incremental = "oneui95-fixture-$index",
                kernelRelease = "6.12.69-android16-6-oneui95-fixture-$index-4k")
            val previous = current.copy(incremental = "before-beta-fixture-$index",
                kernelRelease = "6.12.69-android16-6-before-beta-fixture-$index-4k")
            assertTrue(model, DfPort.matches(current))
            assertEquals(model, SusfsTarget.Supported, susfsTargetFor(current))
            val before = DfCatalog.forDevice(catalog(), previous)
            val after = DfCatalog.forDevice(catalog(), current)
            KernelSuFlavor.entries.forEach { flavor ->
                val old = requireNotNull(before.resolveFor(previous, flavor))
                val selected = requireNotNull(after.resolveFor(current, flavor))
                assertEquals(setOf(model), selected.models)
                assertEquals(DfPort.firmwareFor(current), selected.firmware)
                assertEquals(old.kernelSu, selected.kernelSu)
                assertNotEquals(old.profileId, selected.profileId)
                assertNull(backendRunRefusal(selected, current, flavor, null, false))
                assertNotNull(backendRunRefusal(old, current, flavor, null, false))
                assertEquals(selected, DfCatalog.requireCurrent(catalog(), current, selected))
                assertTrue(runCatching { DfCatalog.requireCurrent(catalog(), current, old) }.isFailure)
            }
        }
    }

    @Test fun verifiedBetaRunRenewsExistingBootPolicyForEachBackend() {
        val current = beta()
        val recorded = DfPort.firmwares.getValue("bzig")
        val previous = current.copy(model = recorded.model, device = recorded.device,
            incremental = recorded.incremental, kernelRelease = recorded.kernelRelease)
        val before = DfCatalog.forDevice(catalog(), previous)
        val after = DfCatalog.forDevice(catalog(), current)
        KernelSuFlavor.entries.forEach { flavor ->
            val old = requireNotNull(before.resolveFor(previous, flavor))
            val selected = requireNotNull(after.resolveFor(current, flavor))
            val policy = EarlyRootConfig(true, true, flavor.id, previous.model, previous.device,
                previous.incremental, previous.kernelRelease, requireNotNull(old.kernelSu.sha256),
                60, true, false, true, susfsAutoEnable = true)
            assertFalse(policy.matches(current, selected))
            val renewed = requireNotNull(policy.afterVerifiedRun(current, selected))
            assertTrue(renewed.matches(current, selected))
            assertEquals(0, renewed.settleSeconds)
            assertTrue(renewed.disableModules)
            assertTrue(renewed.protectImages)
            assertTrue(renewed.susfsAutoEnable)
            assertNull(policy.copy(rootOnBoot = false).afterVerifiedRun(current, selected))
            assertNull(policy.afterVerifiedRun(current.copy(incremental = "another-build"), selected))
        }
    }
}
