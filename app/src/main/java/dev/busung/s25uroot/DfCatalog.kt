package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

/** Closed DirtyFrag payloads, with profiles bound to eligible S26 Ultra devices at runtime. */
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
     * Opt-out of pre-load image protection, read by the kernel module.
     *
     * Protection defaults on with no grant needed (the module sets the flags
     * itself in kernel context), so opting out is the state that must be
     * recorded: staging removes this flag when the setting is on and writes
     * it when off. Same manual semantics as the wipe flag.
     */
    const val NO_RO_FLAG_NAME = "df-noro"

    /**
     * Pre-load module-disable request, read by the kernel module.
     *
     * Mirrors the M3Q toggle (an empty `disable` file per module dir, which
     * is what managers themselves honor) but enforced pre-load in kernel
     * context, so it needs no grant. Same arming as the wipe flag.
     */
    const val DISABLE_FLAG_NAME = "df-disable-modules"

    /**
     * One profile per firmware and backend, each staging that backend's own daemon into
     * the native handoff, so manager pairing stays honest per flavour.
     * on purpose: pointing other flavours at one binary would be the
     * flavour mix-up the profile model exists to prevent. The vendored diabl0w
     * ksud is currently unused: the proven-good fallback if a flavour's own
     * daemon ever fails against a new kernel.
     */
    fun parse(bytes: ByteArray): List<TargetProfile> {
        val manifest = SupportManifest.parse(bytes)
        val expectedIds = DfPort.firmwares.keys.flatMap { firmwareId ->
            KernelSuFlavor.entries.map { "df-$firmwareId-${it.id}" }
        }.toSet()
        require(
            manifest.ignored.isEmpty() && manifest.targets.size == expectedIds.size &&
                manifest.targets.map { it.profileId }.toSet() == expectedIds,
        ) { "The DirtyFrag bundle must contain every registered firmware/backend exactly once" }
        return manifest.targets.map { profile ->
            val firmware = requireNotNull(DfPort.firmwares.entries.singleOrNull { it.value == profile.firmware }) {
                "Unrecognized bundled DirtyFrag firmware"
            }
            require(profile.profileId == "df-${firmware.key}-${profile.flavor.id}")
            require(profile.models == setOf(firmware.value.model))
            require(profile.kernelVersions == setOf(firmware.value.kernelRelease))
            require(profile.kernelSu.url == "asset://azhl/${profile.flavor.id}/ksud")
            require(profile.exploit.url == DF_NATIVE_URL)
            require(profile.kernelSu.sha256 != null && profile.kernelSu.size > 0)
            require(profile.kernelSuVersion == azhlReleaseVersion(profile.flavor))
            require(profile.routePolicy == route && !profile.requiresFreshP0Session)
            profile.copy(sourceId = SOURCE, sourceLabel = "${firmware.key.uppercase()} bundled payloads — hardware testing required")
        }
    }

    /**
     * Whether this profile roots through DirtyFrag rather than a shell payload.
     * Carried on the exploit marker, mirroring [AzhlCatalog.isM3qPayload].
     */
    fun isDfPayload(profile: TargetProfile): Boolean =
        profile.exploit.url == DF_NATIVE_URL

    /** Regional builds share verified payload bytes, while recording their own exact identity. */
    fun forDevice(bytes: ByteArray, snapshot: DeviceSnapshot): List<TargetProfile> {
        val bundled = parse(bytes)
        val firmware = DfPort.firmwareFor(snapshot) ?: return bundled
        if (bundled.any { it.firmware == firmware }) return bundled
        val identity = JSONArray(listOf(firmware.model, firmware.device, firmware.incremental,
            firmware.kernelRelease, firmware.sdk, firmware.abi, firmware.pageSize)).toString()
        val identityHash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).toHex()
        val templates = bundled.filter { it.firmware == DfPort.firmwares.getValue("bzig") }
        return bundled + templates.map { profile ->
            profile.copy(
                profileId = "df-s26-$identityHash-${profile.flavor.id}",
                displayName = "${firmware.model} / ${firmware.incremental} / DirtyFrag / ${profile.flavor.label}",
                models = setOf(firmware.model),
                kernelVersions = setOf(firmware.kernelRelease),
                firmware = firmware,
                sourceLabel = "S26 Ultra bundled payloads — kernel family matched, hardware testing required",
            )
        }
    }

    fun load(context: Context, snapshot: DeviceSnapshot = DeviceSnapshot.current()): List<TargetProfile> =
        context.assets.open("df/catalog.json").use { forDevice(it.readBytes(), snapshot) }

    /** Recheck current firmware and the entire APK-owned profile before writing the staged daemon. */
    fun requireCurrent(bytes: ByteArray, snapshot: DeviceSnapshot, profile: TargetProfile): TargetProfile =
        requireNotNull(forDevice(bytes, snapshot).singleOrNull { it == profile && it.matches(snapshot) }) {
            "The selected payload is not the current DirtyFrag bundle for this device. Select a bundled backend."
        }

    fun stage(context: Context, profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        val snapshot = DeviceSnapshot.current()
        context.assets.open("df/catalog.json").use {
            requireCurrent(it.readBytes(), snapshot, profile)
        }
        onProgress("DirtyFrag target ${snapshot.model} / ${snapshot.incremental}; kernel ${snapshot.kernelRelease}")
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
        } else {
            File(target.parentFile, WIPE_FLAG_NAME).delete()
        }
        if (AppPreferences.partitionReadOnlyMode(context)) {
            File(target.parentFile, NO_RO_FLAG_NAME).delete()
            onProgress("Image protection armed pre-load (no grant needed)")
        } else if (!File(target.parentFile, NO_RO_FLAG_NAME).isFile) {
            File(target.parentFile, NO_RO_FLAG_NAME).writeText("1\n")
        }
        if (AppPreferences.disableKsuModules(context)) {
            File(target.parentFile, DISABLE_FLAG_NAME).writeText("1\n")
            onProgress("Module disable armed before loading")
        } else {
            File(target.parentFile, DISABLE_FLAG_NAME).delete()
        }
        return VerifiedPayloads(profile, library, target, PayloadOrigin.Bundled)
    }
}
