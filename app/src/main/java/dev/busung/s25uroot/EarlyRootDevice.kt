package dev.busung.s25uroot

import java.io.File

/** The persistent boot profile must not record a temporary SusFS uname override. */
internal object EarlyRootDevice {
    fun current(): DeviceSnapshot {
        val snapshot = DeviceSnapshot.current()
        val release = runCatching { File("/proc/sys/kernel/osrelease").readText().trim() }
            .getOrElse { File("/proc/version").readText().trim().split(Regex("\\s+")).getOrNull(2).orEmpty() }
        require(release.isNotBlank() && release.length <= 64 && release.none(Char::isWhitespace)) {
            "Cannot read the original kernel release for the boot profile"
        }
        return snapshot.copy(kernelRelease = release)
    }
}
