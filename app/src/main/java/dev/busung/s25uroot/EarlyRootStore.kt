package dev.busung.s25uroot

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Only boot policy and public firmware identity go into device-protected storage. */
internal data class EarlyRootConfig(
    val enabled: Boolean,
    val rootOnBoot: Boolean,
    val flavor: String,
    val model: String,
    val device: String,
    val incremental: String,
    val kernel: String,
    val daemonHash: String,
    val settleSeconds: Int,
    val disableModules: Boolean,
    val wipeModules: Boolean,
    val protectImages: Boolean,
    val susfsAutoEnable: Boolean = false,
    val restartAfterRoot: Boolean = false,
) {
    val requested: Boolean get() = enabled && rootOnBoot
    val stageOptions get() = DfStageOptions(disableModules, wipeModules, protectImages)

    fun matches(snapshot: DeviceSnapshot, profile: TargetProfile): Boolean =
        model == snapshot.model && device == snapshot.device && incremental == snapshot.incremental &&
            kernel == snapshot.kernelRelease && flavor == profile.flavor.id &&
            daemonHash == profile.kernelSu.sha256 && profile.matches(snapshot) && DfCatalog.isDfPayload(profile)

    /** Renew only an existing opt-in after this exact bundled backend has worked manually. */
    fun afterVerifiedRun(snapshot: DeviceSnapshot, profile: TargetProfile): EarlyRootConfig? {
        if (!requested || flavor != profile.flavor.id || !DfCatalog.isDfPayload(profile) || !profile.matches(snapshot)) return null
        return copy(model = snapshot.model, device = snapshot.device, incremental = snapshot.incremental,
            kernel = snapshot.kernelRelease, daemonHash = requireNotNull(profile.kernelSu.sha256), settleSeconds = 0)
    }

    fun json(): JSONObject = JSONObject().put("api", 1).put("enabled", enabled).put("rootOnBoot", rootOnBoot)
        .put("flavor", flavor).put("model", model).put("device", device).put("incremental", incremental)
        .put("kernel", kernel).put("daemonHash", daemonHash).put("settleSeconds", settleSeconds)
        .put("disableModules", disableModules).put("wipeModules", wipeModules).put("protectImages", protectImages)
        .put("susfsAutoEnable", susfsAutoEnable).put("restartAfterRoot", restartAfterRoot)

    companion object {
        fun parse(json: JSONObject): EarlyRootConfig {
            require(json.getInt("api") == 1)
            val config = EarlyRootConfig(json.getBoolean("enabled"), json.getBoolean("rootOnBoot"),
                json.getString("flavor"), json.getString("model"), json.getString("device"),
                json.getString("incremental"), json.getString("kernel"), json.getString("daemonHash"),
                json.getInt("settleSeconds"), json.getBoolean("disableModules"),
                json.getBoolean("wipeModules"), json.getBoolean("protectImages"),
                json.optBoolean("susfsAutoEnable", false), json.optBoolean("restartAfterRoot", false))
            require(KernelSuFlavor.fromId(config.flavor) != null)
            require(config.daemonHash.matches(Regex("[0-9a-f]{64}")))
            require(config.settleSeconds in BootSettle.allowedSeconds)
            return config
        }
    }
}

/** Policy and results are shared through atomic records, with process-wide file locks on updates. */
internal object EarlyRootStore {
    private fun file(context: Context, name: String) = SharedJsonFile(File(
        context.createDeviceProtectedStorageContext().filesDir, "early-root-$name.json",
    ))

    fun config(context: Context): EarlyRootConfig? = file(context, "config").read()?.let(EarlyRootConfig::parse)
    fun requested(context: Context): Boolean = runCatching { config(context)?.requested == true }.getOrDefault(false)
    fun save(context: Context, config: EarlyRootConfig) = file(context, "config").write(config.json())

    fun disable(context: Context) = file(context, "config").transaction { previous, save ->
        previous?.let { save(EarlyRootConfig.parse(it).copy(enabled = false).json()) }
        Unit
    }

    /** Read-modify-write stays under the same lock as renewal and opt-out. */
    fun syncOptions(context: Context) = file(context, "config").transaction { previous, save ->
        val current = previous?.let(EarlyRootConfig::parse) ?: return@transaction
        save(current.copy(
            enabled = current.enabled && current.flavor == AppPreferences.kernelsuFlavor(context).id,
            rootOnBoot = AppPreferences.bootRootMode(context),
            settleSeconds = 0,
            disableModules = AppPreferences.disableKsuModules(context),
            wipeModules = AppPreferences.wipeModuleState(context),
            protectImages = AppPreferences.partitionReadOnlyMode(context),
            susfsAutoEnable = AppPreferences.susfsAutoEnable(context),
            restartAfterRoot = AppPreferences.restartAfterRoot(context),
        ).json())
    }

    fun refreshAfterVerifiedRun(context: Context, profile: TargetProfile) {
        val snapshot = DeviceSnapshot.current()
        if (!DfCatalog.isDfPayload(profile)) return
        context.assets.open("df/catalog.json").use { DfCatalog.requireCurrent(it.readBytes(), snapshot, profile) }
        file(context, "config").transaction { previous, save ->
            val current = previous?.let(EarlyRootConfig::parse) ?: return@transaction
            current.afterVerifiedRun(snapshot, profile)?.let { save(it.json()) }
        }
    }

    fun result(context: Context): JSONObject? = runCatching { file(context, "result").read() }.getOrNull()

    /** Keep a failed or still-running native handoff until verification or a new kernel boot. */
    fun protectsStaging(context: Context): Boolean {
        val boot = kernelBootToken() ?: return false
        val result = result(context) ?: return false
        return result.optString("boot") == boot && result.optString("state") in setOf("running", "failed") &&
            runCatching { DfBootAttempt.claimed(context, boot) }.getOrDefault(true)
    }

    fun record(context: Context, token: String, state: String, detail: String,
        startedLocked: Boolean, finishedLocked: Boolean = false, finalized: Boolean = false,
        evidence: JSONObject? = null) {
        val next = JSONObject().put("boot", token).put("state", state)
            .put("detail", detail.take(1600)).put("startedLocked", startedLocked)
            .put("finishedLocked", finishedLocked).put("uptimeMs", BootSettle.elapsedMillis())
            .put("finalized", finalized).apply { evidence?.let { put("launch", it) } }
        file(context, "result").transaction { previous, save ->
            if (previous != null && previous.optString("boot") != token) file(context, "previous-result").write(previous)
            save(EarlyRootResult.merge(previous, next))
        }
    }

    fun finalized(context: Context) = file(context, "result").transaction { current, save ->
        current?.let { save(it.put("finalized", true)) }
        Unit
    }

    /** Only the worker updates setup progress, preserving the original root/unlock evidence. */
    fun <T> updateVerified(context: Context, token: String, update: (EarlyRootProgress) -> T): T =
        file(context, "result").transaction { previous, save ->
            val current = checkNotNull(previous) { "The early root result is unavailable" }
            check(current.optString("boot") == token && current.optString("state") == "verified") {
                "The verified kernel boot changed"
            }
            val value = update(EarlyRootProgress(current))
            save(current)
            value
        }

    fun finalizationFailed(context: Context, detail: String) = file(context, "result").transaction { current, save ->
        current?.let { save(it.put("postUnlockDetail", detail.take(1600))) }
        Unit
    }
}
