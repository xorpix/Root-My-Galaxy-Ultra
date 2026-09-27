package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the cache recorded for one verified payload.
 *
 * Our own shape rather than a serialised catalog: only this app reads it back, and a copy of the
 * feed's manifest would imply a compatibility promise with a schema this file has nothing to do
 * with. It holds exactly what is needed to re-derive the run and to check it again on the way out.
 */
internal data class CachedPayload(
    val id: String,
    val profileId: String,
    val displayName: String,
    val models: List<String>,
    val kernelVersions: List<String>,
    val requiresFreshP0Session: Boolean,
    val routePolicy: ExploitRoutePolicy,
    val exploit: RemoteArtifact,
    val kernelSu: RemoteArtifact,
    /**
     * Which KernelSU this entry's daemon and module belong to, carried so a cached run stays the one
     * the user chose.
     *
     * Not re-derived from the setting on the way back in, because the setting can be changed between
     * caching and running: an offline run that read it again would install one flavour's daemon from a
     * payload built for the other - the exact mix-up the flavour exists to prevent.
     */
    val flavor: KernelSuFlavor = KernelSuFlavor.Default,
    /**
     * The KernelSU release the cached daemon is built from, when the feed declared one.
     *
     * Carried for the same reason [flavor] is: an offline run loads this exact daemon, so the version
     * it is belongs to the cache rather than to whatever the feed says today. Null in a cache written
     * from an entry that declared none.
     */
    val kernelSuVersion: String? = null,
    /** Where this payload came from, so an offline run can still name its source. */
    val sourceId: String = "",
    val sourceLabel: String = "",
    val sourceCommit: String = "",
    /** The bundled root helper this payload was verified against, at the time it was cached. */
    val helperSha256: String,
    val helperSize: Long,
    val firmware: FirmwareRequirement? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("profileId", profileId)
        put("displayName", displayName)
        put("models", JSONArray(models))
        put("kernelVersions", JSONArray(kernelVersions))
        put("requiresFreshP0Session", requiresFreshP0Session)
        put("routePolicy", routePolicy.toJsonObject())
        put("exploit", exploit.toJson())
        put("kernelSu", kernelSu.toJson())
        put("flavor", flavor.id)
        kernelSuVersion?.let { put("kernelSuVersion", it) }
        put("sourceId", sourceId)
        put("sourceLabel", sourceLabel)
        put("sourceCommit", sourceCommit)
        put("helperSha256", helperSha256)
        put("helperSize", helperSize)
        firmware?.let { put("firmware", it.toJsonObject()) }
    }.toString()

    /** The profile a run should use, rebuilt from what was cached. */
    fun profile(): TargetProfile = TargetProfile(
        profileId = profileId,
        displayName = displayName,
        models = models.toSet(),
        kernelVersions = kernelVersions.toSet(),
        requiresFreshP0Session = requiresFreshP0Session,
        routePolicy = routePolicy,
        exploit = exploit,
        kernelSu = kernelSu,
        flavor = flavor,
        kernelSuVersion = kernelSuVersion,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceCommit = sourceCommit,
        firmware = firmware,
    )

    companion object {
        fun parse(text: String): CachedPayload {
            val json = JSONObject(text)
            return CachedPayload(
                id = json.getString("id"),
                profileId = json.getString("profileId"),
                displayName = json.getString("displayName"),
                models = json.getJSONArray("models").strings(),
                kernelVersions = json.getJSONArray("kernelVersions").strings(),
                requiresFreshP0Session = json.optBoolean("requiresFreshP0Session", false),
                routePolicy = ExploitRoutePolicy.parse(json.optJSONObject("routePolicy")),
                exploit = json.getJSONObject("exploit").artifact(),
                kernelSu = json.getJSONObject("kernelSu").artifact(),
                // Absent in a cache written before flavours and source identity were recorded, which is
                // why each read falls back instead of requiring the key: the payload itself is still
                // usable, and the entry it rebuilds is the same one an older build would have run.
                flavor = if (!json.has("flavor")) KernelSuFlavor.Default else {
                    requireNotNull(KernelSuFlavor.fromId(json.getString("flavor"))) {
                        "The cached payload declares an unknown root backend"
                    }
                },
                // Absent in a cache written before the feed declared versions, and in one written from
                // an entry that declares none: both mean the offer falls back to the flavour's own.
                kernelSuVersion = json.optString("kernelSuVersion").trim().takeIf(String::isNotEmpty),
                sourceId = json.optString("sourceId"),
                sourceLabel = json.optString("sourceLabel"),
                sourceCommit = json.optString("sourceCommit"),
                helperSha256 = json.getString("helperSha256"),
                helperSize = json.getLong("helperSize"),
                firmware = json.firmwareRequirement(),
            )
        }

        private fun JSONArray.strings(): List<String> = buildList {
            for (index in 0 until length()) add(getString(index))
        }
    }
}

private fun RemoteArtifact.toJson(): JSONObject = JSONObject().apply {
    put("url", url)
    put("size", size)
    put("verifySize", verifySize)
    sha256?.let { put("sha256", it) }
}

private fun JSONObject.artifact(): RemoteArtifact = RemoteArtifact(
    url = getString("url"),
    size = getLong("size"),
    verifySize = optBoolean("verifySize", true),
    sha256 = optString("sha256").trim().takeIf(String::isNotEmpty),
)

/**
 * The identity of a cached payload.
 *
 * Built from the hashes of what was cached rather than from a timestamp or a counter, so the same
 * verified payload caches to the same id and re-publishing it is a no-op instead of an accumulating
 * copy. The helper is part of it because the payload only works with the helper it was verified
 * against: an app update that ships a different one must not be able to load the old pairing.
 */
internal fun knownGoodId(exploitSha256: String, kernelSuSha256: String, helperSha256: String): String =
    "v1-${exploitSha256.take(16)}-${kernelSuSha256.take(16)}-${helperSha256.take(16)}"

/**
 * Why a cached payload may not be used, or null when it may.
 *
 * Pure, because this is the decision the whole cache rests on and it has to be reviewable without a
 * device: a payload cached for another model, another kernel version, another profile, or a helper
 * the APK no longer ships is not a payload this run may use.
 */
internal fun cacheRejectionReason(
    cached: CachedPayload,
    helperSha256: String,
    helperSize: Long,
    snapshot: DeviceSnapshot,
    requestedProfileId: String?,
): String? = when {
    cached.helperSha256 != helperSha256 || cached.helperSize != helperSize ->
        "The cached payload was verified against a different root helper; run an online install to refresh it"
    requestedProfileId != null && requestedProfileId != cached.profileId ->
        "The cached payload is not the selected target"
    !cached.models.any { it.equals(snapshot.model, ignoreCase = true) } ->
        "The cached payload does not list this model"
    !cached.profile().matchesKernelVersion(snapshot) ->
        "The cached payload does not list this kernel version"
    !cached.profile().matches(snapshot) ->
        "The cached payload does not match this exact firmware identity"
    else -> null
}
