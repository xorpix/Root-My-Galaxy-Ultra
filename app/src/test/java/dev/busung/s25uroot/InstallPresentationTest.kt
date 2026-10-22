package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InstallPresentationTest {
    private val device = DeviceSnapshot(
        manufacturer = "samsung", model = "SM-S948U1", device = "m3q",
        kernelRelease = "6.12.69-android16-6-test-regional-build-4k",
        kernelVersionInfo = "#1 SMP PREEMPT", machine = "aarch64",
        buildId = "test-display", fingerprint = "samsung/m3q",
        androidRelease = "17", sdk = 37, abi = "arm64-v8a", pageSize = 4096,
        incremental = "test-regional-firmware",
    )
    private fun profiles(snapshot: DeviceSnapshot) =
        DfCatalog.forDevice(File("src/main/assets/df/catalog.json").readBytes(), snapshot)

    @Test fun automaticAndMatchingBundledSelectionsHaveNoExtraDialogAcrossRegionalModels() {
        listOf("SM-S948B", "SM-S948B/DS", "SM-S948U", "SM-S948U1", "SM-S948W",
            "SM-S948N", "SM-S9480", "SM-S9480V", "SC-53G", "SCG37").forEach { model ->
            val snapshot = device.copy(model = model)
            assertFalse(model, requiresInstallConfirmation(snapshot, null))
            KernelSuFlavor.entries.forEach { flavor ->
                val profile = requireNotNull(profiles(snapshot).resolveFor(snapshot, flavor))
                assertFalse("$model / $flavor", requiresInstallConfirmation(snapshot, profile))
            }
        }
    }

    @Test fun explicitMismatchesAndExternalSelectionsKeepTheConfirmation() {
        val profile = requireNotNull(profiles(device).resolveFor(device))
        listOf(
            profile.copy(models = profile.models + "SM-S948W", firmware = null),
            profile.copy(sourceId = "external-source"),
            profile.copy(kernelVersions = profile.kernelVersions + "6.18.0", firmware = null),
            profile.copy(exploit = profile.exploit.copy(url = "https://example.invalid/payload")),
        ).forEach { assertTrue(requiresInstallConfirmation(device, it)) }
        assertTrue(requiresInstallConfirmation(device.copy(model = "SM-S948W"), profile))
        assertTrue(requiresInstallConfirmation(device.copy(incremental = "different-firmware"), profile))
        assertTrue(requiresInstallConfirmation(device.copy(
            kernelRelease = "6.12.70-android16-7-other-build-4k"), profile))
    }

    @Test fun matchingAzhlSelectionsAlsoHaveNoExtraDialog() {
        val identity = AzhlPort.identity
        val snapshot = device.copy(
            model = identity.model, incremental = identity.incremental,
            kernelRelease = identity.kernelRelease, sdk = identity.sdk,
        )
        assertFalse(requiresInstallConfirmation(snapshot, null))
        val azhlProfiles = AzhlCatalog.parse(File("src/main/assets/azhl/catalog.json").readBytes())
        KernelSuFlavor.entries.forEach { flavor ->
            val profile = requireNotNull(azhlProfiles.resolveFor(snapshot, flavor))
            assertFalse(requiresInstallConfirmation(snapshot, profile))
            assertTrue(requiresInstallConfirmation(snapshot, profile.copy(sourceId = "external-source")))
            assertTrue(requiresInstallConfirmation(device, profile))
        }
        assertTrue(requiresInstallConfirmation(snapshot,
            requireNotNull(profiles(device).resolveFor(device))))
    }

    @Test fun otherDeviceAndKernelRoutesKeepTheConfirmation() {
        listOf(
            device.copy(model = "SM-S938B", device = "pa3q"),
            device.copy(kernelRelease = "6.18.0-android17-0-test-4k"),
            device.copy(pageSize = 16384),
            device.copy(incremental = ""),
            device.copy(sdk = 35),
        ).forEach { assertTrue(requiresInstallConfirmation(it, null)) }
    }
}
