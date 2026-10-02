package dev.busung.s25uroot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzhlPortTest {
    private val device = DeviceSnapshot(
        manufacturer = "samsung", model = "SM-S948B", device = "m3q",
        kernelRelease = AzhlPort.identity.kernelRelease,
        kernelVersionInfo = "#1 SMP PREEMPT", machine = "aarch64",
        buildId = "BP4A.251205.006.S948BXXS4AZHL", fingerprint = "samsung/m3q",
        androidRelease = "16", sdk = 36, abi = "arm64-v8a", pageSize = 4096,
        incremental = "S948BXXS4AZHL",
    )

    private fun profile(flavor: KernelSuFlavor = KernelSuFlavor.KernelSu) = TargetProfile(
        profileId = "m3q-azhl-${flavor.id}", displayName = "AZHL test fixture",
        models = setOf(device.model), kernelVersions = setOf(device.kernelRelease),
        exploit = RemoteArtifact("https://example.invalid/loader", 1),
        kernelSu = RemoteArtifact("https://example.invalid/ksud", 1),
        flavor = flavor, firmware = AzhlPort.identity,
    )

    @Test fun acceptsOnlyTheCompleteExpectedIdentity() {
        assertTrue(AzhlPort.identity.matches(device))
        val wrong = listOf(
            device.copy(incremental = "S948BXXS4AZHM"),
            device.copy(incremental = ""),
            device.copy(model = "SM-S948U"),
            device.copy(device = "other"),
            device.copy(kernelRelease = "6.12.30-other-build"),
            device.copy(kernelRelease = device.kernelRelease + "-different"),
            device.copy(sdk = 37), device.copy(pageSize = 16384),
            device.copy(abi = "armeabi-v7a"), device.copy(machine = "x86_64"),
            device.copy(manufacturer = "other"),
        )
        wrong.forEach { assertFalse("Unexpected match: $it", AzhlPort.identity.matches(it)) }
    }

    @Test fun aGenericModelRowCannotEnableAzhl() {
        assertFalse(profile().copy(firmware = null).matches(device))
        assertFalse(profile().copy(firmware = null, kernelVersions = setOf("6.12.30")).matches(device))
        assertTrue(profile().matches(device))
    }

    @Test fun aFirmwareRequirementSurvivesSerialization() {
        assertEquals(AzhlPort.identity, FirmwareRequirement.parse(AzhlPort.identity.toJsonObject()))
    }

    @Test fun missingOrMalformedRequirementsAreNotGuessed() {
        assertNull(JSONObject().firmwareRequirement())
        listOf("null", "{}", "false", "\"AZHL\"").forEach { value ->
            val result = runCatching { JSONObject("{\"firmware\":$value}").firmwareRequirement() }
            assertTrue("Accepted malformed requirement: $value", result.isFailure)
        }
    }

    @Test fun missingRequestedBackendDoesNotSelectAnother() {
        assertNull(listOf(profile()).resolveFor(device, KernelSuFlavor.KernelSuNext))
        assertNull(listOf(profile()).resolveFor(device, KernelSuFlavor.ReSukiSU))
    }

    @Test fun eachOfTheThreeBackendsKeepsItsOwnPayload() {
        val catalog = KernelSuFlavor.entries.map(::profile).reversed()
        KernelSuFlavor.entries.forEach { flavor ->
            assertEquals(flavor, catalog.resolveFor(device, flavor)?.flavor)
        }
    }

    @Test fun manualOrCachedSelectionCannotOverrideTheChosenBackend() {
        assertNotNull(backendRunRefusal(profile(), device, KernelSuFlavor.ReSukiSU, null, false))
    }

    @Test fun loadedBackendRequiresRebootIncludingRootFromAnotherApp() {
        val next = profile(KernelSuFlavor.KernelSuNext)
        assertNotNull(backendRunRefusal(next, device, next.flavor, KernelSuFlavor.KernelSu, false))
        assertNotNull(backendRunRefusal(next, device, next.flavor, next.flavor, false))
        assertNotNull(backendRunRefusal(next, device, next.flavor, null, true))
        assertNull(backendRunRefusal(next, device, next.flavor, null, false))
    }

    @Test fun changedFirmwareRefusesAPreviouslySelectedProfile() {
        assertNotNull(backendRunRefusal(profile(), device.copy(incremental = "new"), profile().flavor, null, false))
    }

    @Test fun dedicatedBuildRefusesAnyOtherFirmwareOrDevice() {
        assertNull(AzhlPort.identityRefusal(device))
        assertNotNull(AzhlPort.identityRefusal(device.copy(incremental = "new")))
        assertNotNull(AzhlPort.identityRefusal(device.copy(model = "SM-S938B", device = "pa3q")))
    }

    private fun cached() = CachedPayload(
        id = "fixture", profileId = profile().profileId, displayName = "AZHL cache fixture",
        models = listOf(device.model), kernelVersions = listOf(device.kernelRelease),
        requiresFreshP0Session = false, routePolicy = ExploitRoutePolicy.LEGACY,
        exploit = profile().exploit, kernelSu = profile().kernelSu,
        helperSha256 = "a".repeat(64), helperSize = 64,
        firmware = AzhlPort.identity, kernelSuVersion = "3.3.0",
    )

    @Test fun cachedIdentityAndBackendSurviveRoundTrip() {
        val original = cached().copy(flavor = KernelSuFlavor.ReSukiSU, kernelSuVersion = "4.2.0-rc3")
        val restored = CachedPayload.parse(original.toJson())
        assertEquals(original, restored)
        assertEquals(AzhlPort.identity, restored.profile().firmware)
        assertEquals(KernelSuFlavor.ReSukiSU, restored.profile().flavor)
    }

    @Test fun anUnknownCachedBackendIsRefused() {
        val json = JSONObject(cached().toJson()).put("flavor", "unknown-project")
        assertTrue(runCatching { CachedPayload.parse(json.toString()) }.isFailure)
    }

    @Test fun offlineCacheAcceptsExactReleaseButRefusesFirmwareChange() {
        val cache = cached()
        assertNull(cacheRejectionReason(cache, cache.helperSha256, cache.helperSize, device, cache.profileId))
        assertNotNull(cacheRejectionReason(cache, cache.helperSha256, cache.helperSize,
            device.copy(incremental = "new"), cache.profileId))
        assertNotNull(cacheRejectionReason(cache.copy(firmware = null), cache.helperSha256,
            cache.helperSize, device, cache.profileId))
    }

    @Test fun manifestReadsAndEnforcesFirmwareIdentity() {
        val payload = JSONObject()
            .put("payloadId", "azhl-fixture").put("displayName", "fixture")
            .put("models", listOf(device.model)).put("kernelVersions", listOf(device.kernelRelease))
            .put("firmware", AzhlPort.identity.toJsonObject())
            .put("flavor", "kernelsu-next")
            .put("exploit", JSONObject().put("url", "https://example.invalid/loader").put("size", 1))
            .put("kernelsu", JSONObject().put("url", "https://example.invalid/ksud").put("size", 1))
        val manifest = JSONObject().put("schemaVersion", 3).put("payloads", listOf(payload))
        val parsed = SupportManifest.parse(manifest.toString().toByteArray()).targets.single()
        assertEquals(AzhlPort.identity, parsed.firmware)
        assertTrue(parsed.matches(device))
        assertFalse(parsed.matches(device.copy(incremental = "new")))
    }
}
