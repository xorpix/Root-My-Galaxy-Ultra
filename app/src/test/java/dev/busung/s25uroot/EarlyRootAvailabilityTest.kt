package dev.busung.s25uroot

import org.junit.Assert.*
import org.junit.Test

class EarlyRootAvailabilityTest {
    private val readyHelper = D2Status(installed = true, identityVerified = true, controllable = true, activated = true)

    private fun availability(
        helper: D2Status? = readyHelper,
        device: EarlyRootDeviceSupport = EarlyRootDeviceSupport.Supported,
        rootOnBoot: Boolean = true,
        loadKernelSu: Boolean = true,
    ) = EarlyRootAvailability.current(rootOnBoot, loadKernelSu, device, helper)

    @Test fun masterSwitchShowsItsOwnSetupStep() {
        assertEquals(EarlyRootAvailability.NeedsBootRoot,
            availability(rootOnBoot = false, helper = null, device = EarlyRootDeviceSupport.Checking))
        assertEquals(EarlyRootAvailability.NeedsKernelLoading, availability(loadKernelSu = false))
    }

    @Test fun initialAndFailedDeviceReadsAreNotReportedAsUnsupportedFirmware() {
        assertEquals(EarlyRootAvailability.Checking, availability(device = EarlyRootDeviceSupport.Checking))
        assertEquals(EarlyRootAvailability.DeviceUnavailable, availability(device = EarlyRootDeviceSupport.Unavailable))
        assertEquals(EarlyRootAvailability.Unsupported, availability(device = EarlyRootDeviceSupport.Unsupported))
        assertEquals(EarlyRootAvailability.Ready, availability())
    }

    @Test fun helperUpdateRebootAndActivationHaveDistinctNextSteps() {
        assertEquals(EarlyRootAvailability.Checking, availability(helper = null))
        assertEquals(EarlyRootAvailability.NeedsHelper, availability(D2Status()))
        assertEquals(EarlyRootAvailability.NeedsHelperUpdate, availability(readyHelper.copy(needsUpdate = true)))
        assertEquals(EarlyRootAvailability.NeedsReboot, availability(readyHelper.copy(needsReboot = true)))
        assertEquals(EarlyRootAvailability.NeedsActivation, availability(readyHelper.copy(activated = false)))
    }

    @Test fun activationAndConnectionRecoveryUseTheLatestHelperSnapshot() {
        val snapshots = listOf(readyHelper.copy(controllable = false), readyHelper.copy(activated = false), readyHelper)
        assertEquals(listOf(EarlyRootAvailability.HelperUnavailable, EarlyRootAvailability.NeedsActivation,
            EarlyRootAvailability.Ready), snapshots.map { availability(it) })
    }

    @Test fun activationCannotOverrideHelperIdentityOrUpdateGuards() {
        assertEquals(EarlyRootAvailability.UntrustedHelper, availability(readyHelper.copy(identityVerified = false)))
        assertEquals(EarlyRootAvailability.NeedsHelperUpdate,
            availability(readyHelper.copy(needsUpdate = true, needsReboot = true, activated = false)))
        assertEquals(EarlyRootAvailability.NeedsReboot,
            availability(readyHelper.copy(needsReboot = true, activated = false)))
    }

    @Test fun aFailedRefreshDoesNotReuseAnEarlierReadyStatusOrPretendTheHelperWasUninstalled() {
        assertEquals(EarlyRootAvailability.HelperUnavailable, EarlyRootAvailability.current(
            true, true, EarlyRootDeviceSupport.Supported, readyHelper, helperReadFailed = true))
        assertEquals(EarlyRootAvailability.HelperUnavailable, EarlyRootAvailability.current(
            true, true, EarlyRootDeviceSupport.Supported, null, helperReadFailed = true))
    }

    @Test fun anArmedSettingCanAlwaysBeTurnedOffWhenItsSetupIsUnavailable() {
        EarlyRootAvailability.entries.forEach {
            assertTrue("$it prevented disabling", it.canChange(enabled = true, running = false))
            assertEquals("$it allowed enabling", it == EarlyRootAvailability.Ready,
                it.canChange(enabled = false, running = false))
            assertFalse(it.canChange(enabled = true, running = true))
        }
    }
}
