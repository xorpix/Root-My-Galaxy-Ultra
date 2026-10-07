package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DfFamilyTest {
    private fun catalog() = File("src/main/assets/df/catalog.json").readBytes()
    private val device = DeviceSnapshot(
        manufacturer = "samsung", model = "SM-S948U1", device = "m3q",
        kernelRelease = "6.12.69-android16-6-test-regional-build-4k",
        kernelVersionInfo = "#1 SMP PREEMPT", machine = "aarch64",
        buildId = "test-display", fingerprint = "samsung/m3q",
        androidRelease = "17", sdk = 37, abi = "arm64-v8a", pageSize = 4096,
        incremental = "test-regional-firmware",
    )

    @Test fun regionalAndJapaneseModelsSelectEachOfTheirOwnBundledBackends() {
        val models = listOf("SM-S948B", "SM-S948B/DS", "SM-S948U", "SM-S948U1", "SM-S948W",
            "SM-S948N", "SM-S9480", "SM-S9480V", "SM-S948Q", "SM-S948Z", "SM-S948C",
            "SM-S948D", "SM-S948J", "SC-53G", "SCG37")
        models.forEach { model ->
            val snapshot = device.copy(model = model)
            assertTrue("Family not recognized: $model", DfPort.matches(snapshot))
            assertNull(DfPort.identityRefusal(snapshot))
            val targets = DfCatalog.forDevice(catalog(), snapshot)
            val templates = DfCatalog.parse(catalog()).filter { it.firmware == DfPort.firmwares.getValue("bzig") }
            KernelSuFlavor.entries.forEach { flavor ->
                val profile = requireNotNull(targets.resolveFor(snapshot, flavor))
                assertEquals(DfPort.firmwareFor(snapshot), profile.firmware)
                assertEquals(setOf(model), profile.models)
                assertEquals(setOf(snapshot.kernelRelease), profile.kernelVersions)
                assertEquals(flavor, profile.flavor)
                assertTrue(profile.profileId.startsWith("df-s26-"))
                assertTrue(DfCatalog.isDfPayload(profile))
                assertFalse(profile.routePolicy.prefersShellTransport)
                assertEquals(templates.single { it.flavor == flavor }.kernelSu, profile.kernelSu)
                assertNull(backendRunRefusal(profile, snapshot, flavor, null, false))
                assertEquals(profile, DfCatalog.requireCurrent(catalog(), snapshot, profile))
            }
        }
    }

    @Test fun kernelSelectionUsesTheGkiFamilyAcrossAndroidVersionsAndFirmwareUpdates() {
        val snapshots = listOf(
            device.copy(sdk = 36, androidRelease = "16"),
            device.copy(kernelRelease = "6.12.70-android16-7-other-build-4k", incremental = "new-firmware"),
            device.copy(manufacturer = "Samsung", model = "sm-s948w"),
        )
        snapshots.forEach { snapshot ->
            assertTrue(DfPort.matches(snapshot))
            assertNotNull(DfCatalog.forDevice(catalog(), snapshot).resolveFor(snapshot))
        }
    }

    @Test fun unrelatedHardwareAndUnsupportedKernelFamiliesHaveNoAutomaticProfile() {
        val wrong = listOf(
            device.copy(model = "SM-S947B"), device.copy(model = "SM-S942B"),
            device.copy(model = "SM-S938B"), device.copy(model = "SCG38"),
            device.copy(model = "SC-52G"), device.copy(model = "SM-S948BZVGEUB"),
            device.copy(model = "SM-S948"), device.copy(manufacturer = "other"),
            device.copy(device = "other"), device.copy(machine = "x86_64"),
            device.copy(abi = "armeabi-v7a"), device.copy(pageSize = 16384),
            device.copy(sdk = 35), device.copy(incremental = ""),
            device.copy(kernelRelease = "6.12.69"),
            device.copy(kernelRelease = "6.12.69-android15-6-test-4k"),
            device.copy(kernelRelease = "6.18.5-android17-0-test-4k"),
            device.copy(kernelRelease = "6.1.120-android14-0-test-4k"),
        )
        val bundledCount = DfCatalog.parse(catalog()).size
        wrong.forEach { snapshot ->
            assertFalse("Unexpected DF route: $snapshot", DfPort.matches(snapshot))
            assertNull(DfPort.firmwareFor(snapshot))
            assertNotNull(DfPort.identityRefusal(snapshot))
            val profiles = DfCatalog.forDevice(catalog(), snapshot)
            assertEquals(bundledCount, profiles.size)
            KernelSuFlavor.entries.forEach { assertNull(profiles.resolveFor(snapshot, it)) }
        }
    }

    @Test fun azhlKeepsItsGhostLockRouteAndRecordedDfProfilesKeepTheirIds() {
        val snapshots = DfPort.firmwares.values.map(::snapshot) + snapshot(AzhlPort.identity)
        snapshots.forEach { snapshot ->
            val df = DfCatalog.forDevice(catalog(), snapshot)
            assertEquals(DfCatalog.parse(catalog()), df)
            val all = AzhlCatalog.parse(File("src/main/assets/azhl/catalog.json").readBytes()) + df
            KernelSuFlavor.entries.forEach { flavor ->
                val profile = requireNotNull(all.resolveFor(snapshot, flavor))
                if (snapshot.incremental == AzhlPort.identity.incremental) {
                    assertFalse(DfPort.matches(snapshot))
                    assertTrue(AzhlCatalog.isM3qPayload(profile))
                    assertFalse(DfCatalog.isDfPayload(profile))
                } else {
                    assertTrue(DfCatalog.isDfPayload(profile))
                    assertFalse(profile.profileId.startsWith("df-s26-"))
                }
            }
        }
    }

    @Test fun stagingRefusesSubstitutedPayloadsAndProfilesFromAnotherFirmware() {
        val profile = requireNotNull(DfCatalog.forDevice(catalog(), device).resolveFor(device))
        val replacements = listOf(
            profile.copy(firmware = null),
            profile.copy(kernelSu = profile.kernelSu.copy(url = "asset://df/ksud")),
            profile.copy(kernelSu = profile.kernelSu.copy(sha256 = "a".repeat(64))),
            profile.copy(kernelSuVersion = "9.9.9"),
            profile.copy(routePolicy = profile.routePolicy.copy(attempts = 2)),
            profile.copy(flavor = KernelSuFlavor.KernelSuNext),
            profile.copy(exploit = profile.exploit.copy(url = "https://example.invalid/exploit")),
        )
        replacements.forEach { replacement ->
            assertTrue(runCatching { DfCatalog.requireCurrent(catalog(), device, replacement) }.isFailure)
        }
        assertFalse(replacements.first().matches(device))
        assertFalse(replacements.last().matches(device))
        val updated = device.copy(incremental = "updated-firmware")
        val newProfile = requireNotNull(DfCatalog.forDevice(catalog(), updated).resolveFor(updated))
        assertNotEquals(profile.profileId, newProfile.profileId)
        assertNotNull(backendRunRefusal(profile, updated, profile.flavor, null, false))
        assertTrue(runCatching { DfCatalog.requireCurrent(catalog(), updated, profile) }.isFailure)
        assertTrue(runCatching { DfCatalog.requireCurrent(catalog(), device.copy(model = "SM-S948W"), profile) }.isFailure)
        assertEquals(newProfile, DfCatalog.requireCurrent(catalog(), updated, newProfile))
    }

    @Test fun generatedProfilesAndCachesAreStableAndKeepTheExactFirmware() {
        val profiles = DfCatalog.forDevice(catalog(), device)
        assertEquals(profiles, DfCatalog.forDevice(catalog(), device.copy(
            kernelVersionInfo = "different display text", buildId = "different display", fingerprint = "other")))
        val profile = requireNotNull(profiles.resolveFor(device, KernelSuFlavor.ReSukiSU))
        val cached = CachedPayload(
            id = "fixture", profileId = profile.profileId, displayName = profile.displayName,
            models = profile.models.toList(), kernelVersions = profile.kernelVersions.toList(),
            requiresFreshP0Session = profile.requiresFreshP0Session, routePolicy = profile.routePolicy,
            exploit = profile.exploit, kernelSu = profile.kernelSu, flavor = profile.flavor,
            kernelSuVersion = profile.kernelSuVersion, sourceId = profile.sourceId,
            sourceLabel = profile.sourceLabel, firmware = profile.firmware,
            helperSha256 = "a".repeat(64), helperSize = 1,
        )
        val restored = CachedPayload.parse(cached.toJson())
        assertEquals(profile, restored.profile())
        assertNull(cacheRejectionReason(restored, cached.helperSha256, cached.helperSize, device, profile.profileId))
        assertNotNull(cacheRejectionReason(restored, cached.helperSha256, cached.helperSize,
            device.copy(incremental = "updated"), profile.profileId))
        assertFalse(bootPayloadNeedsShell(preferAttempted = false, attempted = null, cached = restored))
        assertFalse(bootPayloadNeedsShell(preferAttempted = true, attempted = restored, cached = null))
    }

    private fun snapshot(firmware: FirmwareRequirement) = device.copy(
        model = firmware.model, device = firmware.device, incremental = firmware.incremental,
        kernelRelease = firmware.kernelRelease, sdk = firmware.sdk,
        abi = firmware.abi, pageSize = firmware.pageSize,
    )
}
