package dev.busung.s25uroot

internal enum class EarlyRootDeviceSupport { Checking, Supported, Unsupported, Unavailable }

/** One decision supplies both the switch state and the next setup step shown to the user. */
internal enum class EarlyRootAvailability {
    Checking, NeedsBootRoot, NeedsKernelLoading, DeviceUnavailable, Unsupported,
    NeedsHelper, UntrustedHelper, NeedsHelperUpdate, NeedsReboot, NeedsActivation,
    HelperUnavailable, Ready;

    fun canChange(enabled: Boolean, running: Boolean): Boolean = !running && (enabled || this == Ready)

    companion object {
        fun current(
            rootOnBoot: Boolean,
            loadKernelSu: Boolean,
            device: EarlyRootDeviceSupport,
            helper: D2Status?,
            helperReadFailed: Boolean = false,
        ): EarlyRootAvailability = when {
            !rootOnBoot -> NeedsBootRoot
            !loadKernelSu -> NeedsKernelLoading
            device == EarlyRootDeviceSupport.Checking -> Checking
            device == EarlyRootDeviceSupport.Unavailable -> DeviceUnavailable
            device == EarlyRootDeviceSupport.Unsupported -> Unsupported
            helperReadFailed -> HelperUnavailable
            helper == null -> Checking
            !helper.installed -> NeedsHelper
            !helper.identityVerified -> UntrustedHelper
            helper.needsUpdate -> NeedsHelperUpdate
            helper.needsReboot -> NeedsReboot
            !helper.controllable -> HelperUnavailable
            !helper.activated -> NeedsActivation
            else -> Ready
        }
    }
}
