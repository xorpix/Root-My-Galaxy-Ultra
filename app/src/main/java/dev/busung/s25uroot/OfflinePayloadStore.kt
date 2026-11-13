package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest

/** Where a run's payload files came from. */
enum class PayloadOrigin {
    /** Verified against the firmware-specific assets in this APK. */
    Bundled,
    /** Downloaded and verified from a source during this run. */
    Downloaded,

    /** Read from the known-good cache, with no network involved. */
    Cached,

    /**
     * The payload an earlier run attempted, run again from the files that attempt left behind.
     *
     * Its own value rather than [Cached] because the two are not the same claim: one is "this payload
     * completed a verified install", the other is "this is what the failed run was", and only the
     * first may be published back to the cache. The log says which one a run used.
     */
    Attempted,
}

/** What a run is allowed to use as its payload. */
enum class PayloadMode {
    /** Always read the catalog and download: the revision the source is on is the revision used. */
    Online,

    /**
     * Use the last payload that completed a verified run, and no network at all.
     *
     * This is what makes a run possible when the catalog cannot be reached — the API is limited, the
     * network is down, or the device is being used somewhere without one — and it is the mode a
     * boot-time run has to use, because at boot there is no one to wait for a download.
     */
    Offline,
}

/**
 * The last payload that completed a verified run, kept so a later run does not need the network.
 *
 * Nothing is written while the exploit is running: a payload is published only after KernelSU has
 * been verified, which is what makes "known good" mean what it says. Everything it holds is checked
 * again on the way out, so a cache that has been tampered with, or one left behind by an older build
 * with a different helper, is refused rather than run.
 */
internal object KnownGoodPayloadStore {
    private const val PREFERENCES = "known_good_payload"
    private const val ACTIVE = "active"
    private const val ROOT = "payloads/known-good"
    private fun cache(context: Context) = PayloadCache(
        File(context.filesDir, ROOT),
        SharedJsonFile(File(context.filesDir, "payloads/known-good-active.json")),
        legacyActive = { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ACTIVE, null) },
        protect = { Os.chmod(it.absolutePath, 0b100100100) },
    )

    /** Name of the root helper this app bundles, as the manifest's own metadata describes it. */
    const val ROOT_HELPER_LIBRARY = "libcve43499root.so"

    fun hasValid(context: Context, snapshot: DeviceSnapshot = DeviceSnapshot.current()): Boolean = runCatching {
        load(context, snapshot = snapshot)
        true
    }.getOrDefault(false)

    /** What is cached, for a settings row that has to say whether there is anything to fall back on. */
    fun describe(context: Context): CachedPayload? = runCatching { descriptor(context) }.getOrNull()

    /**
     * The target the cached payload names, without reading its files.
     *
     * This is what a run resolves in Offline mode: the catalog is not consulted at all, and the
     * question of whether the cache may be used is answered the same way here as when it is loaded,
     * so a run cannot get past this point and then find the files unusable.
     */
    fun profileFor(context: Context, requestedProfileId: String? = null,
        snapshot: DeviceSnapshot = DeviceSnapshot.current()): TargetProfile {
        val cached = usableDescriptor(context, requestedProfileId, snapshot)
        return cached.profile()
    }

    /**
     * Reads the cached payload, refusing anything that does not belong to this device and this build.
     */
    fun load(context: Context, requestedProfileId: String? = null,
        snapshot: DeviceSnapshot = DeviceSnapshot.current()): VerifiedPayloads {
        val payloads = cache(context).load { cached -> validate(context, cached, requestedProfileId, snapshot) }
        rememberResolvedPayload(context, payloads.profile)
        return payloads
    }

    /**
     * Publishes a payload that has already completed a verified run.
     *
     * The files must match what the profile declares — they were verified during the run, and this
     * verifies them again — and the bundled helper is recorded, so the pairing cannot be changed by
     * an app update without the cache being refused. The copy goes to a temporary directory and is
     * renamed into place, so an interrupted publish leaves the previous cache intact rather than a
     * half-written one that would then be run.
     */
    @Synchronized
    fun publish(context: Context, payloads: VerifiedPayloads): CachedPayload {
        // An attempt counts, because it is a payload from a source too - one that was downloaded and
        // verified by an earlier run, and verified again on the way out of the attempt record. A retry
        // that succeeds at boot should leave the device in the ordinary state, with the payload that
        // just worked as the offline fallback; refusing it would leave the cache describing the payload
        // the device has stopped testing.
        require(
            payloads.origin == PayloadOrigin.Bundled || payloads.origin == PayloadOrigin.Downloaded || payloads.origin == PayloadOrigin.Attempted,
        ) {
            "Only a payload verified from a source can replace the cached one"
        }
        val profile = payloads.profile
        require(profile.matches(DeviceSnapshot.current())) {
            "Only a payload for this device can be cached"
        }
        require(fileMatchesArtifact(payloads.exploit, profile.exploit)) {
            "The exploit failed verification again; refusing to cache it"
        }
        require(fileMatchesArtifact(payloads.kernelSu, profile.kernelSu)) {
            "KernelSU failed verification again; refusing to cache it"
        }
        val helper = bundledRootHelper(context)
        val helperSha256 = helper.sha256 ?: error(
            "The bundled root helper could not be read, so a payload cannot be cached against it",
        )

        val descriptor = CachedPayload(
            id = "", // The cache assigns a new immutable generation when metadata or files change.
            profileId = profile.profileId,
            displayName = profile.displayName,
            models = profile.models.toList(),
            kernelVersions = profile.kernelVersions.toList(),
            requiresFreshP0Session = profile.requiresFreshP0Session,
            routePolicy = profile.routePolicy,
            exploit = profile.exploit,
            kernelSu = profile.kernelSu,
            flavor = profile.flavor,
            kernelSuVersion = profile.kernelSuVersion,
            sourceId = profile.sourceId,
            sourceLabel = profile.sourceLabel,
            sourceCommit = profile.sourceCommit,
            helperSha256 = helperSha256,
            helperSize = helper.size,
            firmware = profile.firmware,
        )

        return cache(context).publish(descriptor, payloads.exploit, payloads.kernelSu)
    }

    /**
     * Drops the cache, so the next offline run is refused instead of falling back to a payload the
     * user has decided against. Nothing clears it automatically: a cached payload that failed could
     * have failed for any reason, and losing the fallback over one bad run would be the wrong trade.
     */
    @Synchronized
    fun clear(context: Context) {
        cache(context).clear()
    }

    /** The descriptor, with the checks that make it usable for this device and this build applied. */
    private fun usableDescriptor(context: Context, requestedProfileId: String?, snapshot: DeviceSnapshot): CachedPayload {
        val cached = descriptor(context)
        validate(context, cached, requestedProfileId, snapshot)
        return cached
    }

    private fun validate(context: Context, cached: CachedPayload, requestedProfileId: String?, snapshot: DeviceSnapshot) {
        val helper = bundledRootHelper(context)
        // A missing helper is its own refusal rather than a digest mismatch: the message for a
        // mismatch tells the user to run an online install, which would not help here.
        val helperSha256 = helper.sha256
            ?: error("The root helper this app bundles could not be read")
        cacheRejectionReason(
            cached = cached,
            helperSha256 = helperSha256,
            helperSize = helper.size,
            snapshot = snapshot,
            requestedProfileId = requestedProfileId,
        )?.let { reason -> error(reason) }
    }

    private fun descriptor(context: Context): CachedPayload = cache(context).describe()

    /**
     * The helper the app bundles, as an artifact-like pair of hash and size.
     *
     * Internal rather than private because the attempt record is held to the same binding: a payload
     * only works with the helper it was verified against, so an app update that ships a different one
     * must not be able to run either an old cache entry or an old attempt.
     */
    internal fun bundledRootHelper(context: Context): RemoteArtifact {
        val file = File(context.applicationInfo.nativeLibraryDir, ROOT_HELPER_LIBRARY)
        if (!file.isFile) return RemoteArtifact(url = "", size = 0, verifySize = false)
        return RemoteArtifact(
            url = file.absolutePath,
            size = file.length(),
            verifySize = false,
            sha256 = sha256Of(file),
        )
    }


}

/**
 * Whether [file] is the artifact the feed declared.
 *
 * The same rule the download path applies, and deliberately so: what makes a cached payload
 * trustworthy is that it is held to the check it passed, not to a weaker one because it is local.
 */
internal fun fileMatchesArtifact(file: File, artifact: RemoteArtifact): Boolean {
    if (!file.isFile) return false
    // A declared digest is the whole check, exactly as on the download path: matching content already
    // proves the length, so a feed that mis-states one does not fail a file whose bytes are right.
    artifact.sha256?.let { declared -> return sha256Of(file) == declared }
    return !artifact.verifySize || file.length() == artifact.size
}

/** Lowercase hex SHA-256 of a file, reading it in chunks rather than into memory. */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
