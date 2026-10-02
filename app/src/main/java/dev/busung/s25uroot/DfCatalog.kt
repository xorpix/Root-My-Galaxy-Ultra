package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest

/** A closed, firmware-specific bundle for the DirtyFrag family. Remote feeds and caches cannot replace these bytes. */
internal object DfCatalog {
    const val SOURCE = "bundled-df"
    val route = ExploitRoutePolicy(attempts = 1, p0OffsetCache = false, prefersShellTransport = false)

    /**
     * diabl0w's KernelSU fork build, shipped as an asset and hash-pinned like
     * every other daemon this app stages. Size and hash must match
     * catalog.json and the file under assets/df/.
     */
    const val DF_KSUD_ASSET = "asset://df/ksud"
    const val DF_KSUD_SIZE = 6028336L
    const val DF_KSUD_SHA256 = "13c8ffddbd52b4c8f75a84773d5c534186b49883b575fffb3830bfd651ef4e6f"

    /**
     * The DirtyFrag native library ships inside this APK (built from the
     * vendored sources, see the dirtyfrag/ directory), not as an asset, so
     * the catalog marks it rather than pinning it. Presence and readability
     * are checked at runtime; the build itself is the integrity guarantee.
     */
    const val DF_NATIVE_URL = "native://libexp.so"
    const val DF_LIB_NAME = "libexp.so"

    /**
     * Pre-load wipe request, read by the kernel module before late-load.
     *
     * DirtyFrag has no pre-daemon root (no helper socket, su only exists
     * after the daemon is up), so the app cannot wipe itself: staging writes
     * this flag next to the staged daemon while the wipe setting is on, and
     * the module — already root in kernel context — clears module state
     * before running late-load, then removes the flag. Skipped while a
     * backend is already live (reboot first). Same manual-off semantics as
     * the M3Q toggle: while the setting is on, every run arms it again.
     */
    const val WIPE_FLAG_NAME = "df-wipe-requested"

    /**
     * One profile per backend, each staging that backend's own daemon into
     * the native handoff, so manager pairing stays honest per flavour.
     * on purpose: pointing other flavours at one binary would be the
     * flavour mix-up the profile model exists to prevent. The vendored diabl0w
     * ksud is currently unused: the proven-good fallback if a flavour's own
     * daemon ever fails against a new kernel.
     */
    fun parse(bytes: ByteArray): List<TargetProfile> {
        val manifest = SupportManifest.parse(bytes)
        require(manifest.ignored.isEmpty() && manifest.targets.size == KernelSuFlavor.entries.size)
        return manifest.targets.map { profile ->
            require(profile.profileId == "df-bzig-${profile.flavor.id}")
            require(profile.firmware == BzigPort.identity)
            require(profile.models == setOf(BzigPort.identity.model))
            require(profile.kernelVersions == setOf(BzigPort.identity.kernelRelease))
            require(profile.kernelSu.url == "asset://azhl/${profile.flavor.id}/ksud")
            require(profile.exploit.url == DF_NATIVE_URL)
            require(profile.kernelSu.sha256 != null && profile.kernelSu.size > 0)
            require(profile.kernelSuVersion == azhlReleaseVersion(profile.flavor))
            require(profile.routePolicy == route && !profile.requiresFreshP0Session)
            profile.copy(sourceId = SOURCE, sourceLabel = "BZIG bundled payloads — hardware testing required")
        }
    }

    /**
     * Whether this profile roots through DirtyFrag rather than a shell payload.
     * Carried on the exploit marker, mirroring [AzhlCatalog.isM3qPayload].
     */
    fun isDfPayload(profile: TargetProfile): Boolean =
        profile.exploit.url == DF_NATIVE_URL

    fun load(context: Context): List<TargetProfile> =
        context.assets.open("df/catalog.json").use { parse(it.readBytes()) }

    fun stage(context: Context, profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        require(load(context).singleOrNull { it == profile } == profile) {
            "The selected payload is not the current BZIG bundle. Select a bundled backend."
        }
        val dest = File(context.createDeviceProtectedStorageContext().getFilesDir().getParentFile(), "ksud")
        check(dest.parentFile.isDirectory || dest.parentFile.mkdirs())
        val daemonArtifact = profile.kernelSu
        val target = dest
        val temporary = File.createTempFile("stage-", ".tmp", target.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.assets.open(daemonArtifact.url.removePrefix("asset://")).use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= daemonArtifact.size) { "Bundled payload exceeds its declared size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(size == daemonArtifact.size && digest.digest().toHex() == daemonArtifact.sha256) {
                "Bundled payload checksum or size mismatch"
            }
            Os.chmod(temporary.absolutePath, 0b111101101)
            Os.rename(temporary.absolutePath, target.absolutePath)
            require(target.canExecute()) { "Staged backend is not executable" }
            onProgress("Verified DirtyFrag: ksud")
        } finally {
            temporary.delete()
        }
        val library = File(context.applicationInfo.nativeLibraryDir, DF_LIB_NAME)
        require(library.isFile && library.canRead()) { "DirtyFrag native library missing from this build" }
        if (AppPreferences.wipeModuleState(context)) {
            File(target.parentFile, WIPE_FLAG_NAME).writeText("1\n")
            onProgress("DirtyFrag wipe armed before loading (turn the reset switch off again after)")
        }
        return VerifiedPayloads(profile, library, target, PayloadOrigin.Bundled)
    }
}
