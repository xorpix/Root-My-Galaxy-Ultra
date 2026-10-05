package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest

/** A closed, firmware-specific bundle. Remote feeds and caches cannot replace these bytes. */
internal object AzhlCatalog {
    const val SOURCE = "bundled-azhl"
    val route = ExploitRoutePolicy(attempts = 1, p0OffsetCache = false, prefersShellTransport = true)

    /**
     * Tested M3Q exploit binaries for the reference backend, extracted from the
     * working M3Q-1.0.9-KSU APK (see m3q-reference/ORIGIN.md). Sizes and hashes
     * must match catalog.json and the files under assets/m3q/.
     */
    const val M3Q_PAYLOAD_ASSET = "asset://m3q/libm3qpayload.so"
    const val M3Q_PAYLOAD_SIZE = 238648L
    const val M3Q_PAYLOAD_SHA256 = "9ceb86833b9ae5da350dc04bc1a77d992680931973da1e9172b4ddd999b404dc"
    const val M3Q_ORACLE_ASSET = "asset://m3q/libm3qoracle.so"
    const val M3Q_ORACLE_SIZE = 137408L
    const val M3Q_ORACLE_SHA256 = "d85008e49bf72395455baefb697fb212e92b5533e0ab53a0e75cff818f064bf7"
    const val M3Q_ROOT_ASSET = "asset://m3q/libm3qroot.so"
    const val M3Q_ROOT_SIZE = 24232L
    const val M3Q_ROOT_SHA256 = "39b018c3648c26fc7e801f6ec7a25018b3ef8544033afadbad4b36dd714d9d59"

    /** True when this profile's exploit is the tested M3Q payload.
     *
     * The exploit is backend-agnostic: it only obtains root, and the backend
     * comes from whichever ksud is staged at late-load time. All three
     * flavors therefore share the tested payload and differ by ksud. */
    fun isM3qPayload(profile: TargetProfile): Boolean =
        profile.exploit.url == M3Q_PAYLOAD_ASSET

    fun parse(bytes: ByteArray): List<TargetProfile> {
        val manifest = SupportManifest.parse(bytes)
        require(manifest.ignored.isEmpty() && manifest.targets.size == KernelSuFlavor.entries.size)
        require(manifest.targets.map { it.flavor }.toSet() == KernelSuFlavor.entries.toSet())
        return manifest.targets.map { profile ->
            require(profile.profileId == "m3q-azhl-${profile.flavor.id}")
            require(profile.firmware == AzhlPort.identity)
            require(profile.models == setOf(AzhlPort.identity.model))
            require(profile.kernelVersions == setOf(AzhlPort.identity.kernelRelease))
            // All flavors share the original M3Q payload (the exploit
            // is backend-agnostic; the backend comes from the staged ksud).
            // The FIXED4-derived loader is no longer referenced by any profile.
            require(profile.exploit.url == M3Q_PAYLOAD_ASSET)
            require(profile.kernelSu.url == "asset://azhl/${profile.flavor.id}/ksud")
            require(profile.exploit.sha256 != null && profile.kernelSu.sha256 != null)
            require(profile.exploit.size > 0 && profile.kernelSu.size > 0)
            require(profile.kernelSuVersion == azhlReleaseVersion(profile.flavor))
            require(profile.routePolicy == route && !profile.requiresFreshP0Session)
            profile.copy(sourceId = SOURCE, sourceLabel = "AZHL bundled payloads — hardware testing required")
        }
    }

    fun load(context: Context): List<TargetProfile> =
        context.assets.open("azhl/catalog.json").use { parse(it.readBytes()) }

    fun stage(context: Context, profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        require(load(context).singleOrNull { it.flavor == profile.flavor } == profile) {
            "The selected payload is not the current AZHL bundle. Select a bundled backend."
        }
        val directory = File(context.filesDir, "azhl-payloads/${profile.flavor.id}")
        check(directory.isDirectory || directory.mkdirs())
        fun copy(artifact: RemoteArtifact, name: String): File {
            val target = File(directory, name)
            val temporary = File.createTempFile("stage-", ".tmp", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                context.assets.open(artifact.url.removePrefix("asset://")).use { source ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= artifact.size) { "Bundled payload exceeds its declared size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(size == artifact.size && digest.digest().toHex() == artifact.sha256) {
                    "Bundled payload checksum or size mismatch"
                }
                Os.chmod(temporary.absolutePath, 0b100100100)
                Os.rename(temporary.absolutePath, target.absolutePath)
                onProgress("Verified ${profile.flavor.label}: $name")
                return target
            } finally {
                temporary.delete()
            }
        }
        return VerifiedPayloads(profile, copy(profile.exploit, "loader.so"),
            copy(profile.kernelSu, "ksud"), PayloadOrigin.Bundled)
    }
}

internal fun azhlReleaseVersion(flavor: KernelSuFlavor): String = when (flavor) {
    KernelSuFlavor.KernelSu -> "3.3.0"
    KernelSuFlavor.KernelSuNext -> "3.4.0"
    KernelSuFlavor.ReSukiSU -> "4.2.0-rc3"
}

internal fun azhlDriverVersion(flavor: KernelSuFlavor): Int = when (flavor) {
    KernelSuFlavor.KernelSu -> 32661
    KernelSuFlavor.KernelSuNext -> 33319
    KernelSuFlavor.ReSukiSU -> 35212
}

internal fun azhlEnvironment(flavor: KernelSuFlavor, disableModules: Boolean): Array<String> = arrayOf(
    "AZHL_BACKEND=${flavor.id}",
    "AZHL_DRIVER_VERSION=${azhlDriverVersion(flavor)}",
    "AZHL_DISABLE_MODULES=${if (disableModules) 1 else 0}",
)

internal fun azhlBackendVerified(output: String, flavor: KernelSuFlavor): Boolean =
    output.lineSequence().any { it.trim() == "AZHL_BACKEND_VERIFIED ${flavor.id} ${azhlDriverVersion(flavor)}" }

internal fun verifiedAzhlControl(output: String, flavor: KernelSuFlavor): KernelSuControl? {
    if (!azhlBackendVerified(output, flavor)) return null
    val report = output.lineSequence().firstNotNullOfOrNull { line ->
        if (line.startsWith("KernelSU control verified ")) parseControlReport(line) else null
    }
    return report?.takeIf { it.version == azhlDriverVersion(flavor) && (it.flags and 5) == 5 }
}

/**
 * Verifies the daemon's own control line for the selected backend.
 *
 * M3Q's helper prints `KernelSU control verified version=%u flags=0x%x
 * uapi=%u features=0x%x` (same shape ours parses). Accept only the selected
 * flavor's driver version with both required flags (su_allow + enable),
 * mirroring [verifiedAzhlControl] without our marker line, which M3Q never
 * prints. Next/ReSukiSU are forks: if their flag layout differs, this fails
 * closed and the log shows the reported line.
 */
internal fun verifiedM3qControl(output: String, flavor: KernelSuFlavor): KernelSuControl? {
    val report = output.lineSequence().firstNotNullOfOrNull { line ->
        if (line.startsWith("KernelSU control verified ")) parseControlReport(line) else null
    }
    return report?.takeIf { M3qLaunch.acceptsControl(flavor.id, it.version, it.flags, it.uapi) }
}
