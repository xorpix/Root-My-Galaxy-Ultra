package dev.busung.s25uroot

import java.io.File
import dev.experimental.rmgu.d2.KernelIdentity

/** The persistent boot profile must not record a temporary SusFS uname override. */
internal object EarlyRootDevice {
    fun current(helper: D2Status? = null): DeviceSnapshot {
        val snapshot = DeviceSnapshot.current()
        return snapshot.copy(kernelRelease = originalKernelRelease(helper))
    }

    /** Only an authenticated, matching running helper can supply the otherwise restricted read. */
    internal fun originalKernelRelease(
        helper: D2Status? = null,
        read: (String) -> String = { File(it).readText() },
    ): String {
        if (helper != null && helper.installed && helper.identityVerified && helper.controllable &&
            !helper.needsUpdate && !helper.needsReboot && KernelIdentity.valid(helper.kernelRelease)) {
            return requireNotNull(helper.kernelRelease)
        }
        return checkNotNull(KernelIdentity.read { read(it) }) {
            "The boot helper could not read the original kernel release. Update the helper and fully reboot."
        }
    }
}
