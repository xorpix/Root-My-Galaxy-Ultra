package dev.busung.s25uroot

import java.io.File

/** The persistent boot profile must not record a temporary SusFS uname override. */
internal object EarlyRootDevice {
    fun current(): DeviceSnapshot {
        val snapshot = DeviceSnapshot.current()
        return snapshot.copy(kernelRelease = originalKernelRelease())
    }

    /** Retry the independent proc source after an unreadable, empty or malformed sysctl. */
    internal fun originalKernelRelease(read: (String) -> String = { File(it).readText() }): String {
        fun valid(value: String) = value.isNotBlank() && value.length <= 64 &&
            value.none { it.isWhitespace() || it.isISOControl() }

        val sysctl = runCatching { read("/proc/sys/kernel/osrelease").trim() }.getOrNull()
        if (sysctl != null && valid(sysctl)) return sysctl
        val fields = runCatching { read("/proc/version").trim().split(Regex("\\s+"), limit = 4) }.getOrNull()
        val release = fields?.takeIf { it.size >= 3 && it[0] == "Linux" && it[1] == "version" }?.get(2)
        check(release != null && valid(release)) { "Cannot read the original kernel release for the boot profile" }
        return release
    }
}
