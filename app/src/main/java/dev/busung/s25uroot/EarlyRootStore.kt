package dev.busung.s25uroot

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
) {
    val requested: Boolean get() = enabled && rootOnBoot
    val stageOptions get() = DfStageOptions(disableModules, wipeModules, protectImages)

    fun matches(snapshot: DeviceSnapshot, profile: TargetProfile): Boolean =
        model == snapshot.model && device == snapshot.device && incremental == snapshot.incremental &&
            kernel == snapshot.kernelRelease && flavor == profile.flavor.id &&
            daemonHash == profile.kernelSu.sha256 && profile.matches(snapshot) && DfCatalog.isDfPayload(profile)

    fun json(): JSONObject = JSONObject().put("api", 1).put("enabled", enabled).put("rootOnBoot", rootOnBoot)
        .put("flavor", flavor).put("model", model).put("device", device).put("incremental", incremental)
        .put("kernel", kernel).put("daemonHash", daemonHash).put("settleSeconds", settleSeconds)
        .put("disableModules", disableModules).put("wipeModules", wipeModules).put("protectImages", protectImages)

    companion object {
        fun parse(json: JSONObject): EarlyRootConfig {
            require(json.getInt("api") == 1)
            val config = EarlyRootConfig(json.getBoolean("enabled"), json.getBoolean("rootOnBoot"),
                json.getString("flavor"), json.getString("model"), json.getString("device"),
                json.getString("incremental"), json.getString("kernel"), json.getString("daemonHash"),
                json.getInt("settleSeconds"), json.getBoolean("disableModules"),
                json.getBoolean("wipeModules"), json.getBoolean("protectImages"))
            require(KernelSuFlavor.fromId(config.flavor) != null)
            require(config.daemonHash.matches(Regex("[0-9a-f]{64}")))
            require(config.settleSeconds in BootSettle.allowedSeconds)
            return config
        }
    }
}

/** Files are re-opened on every read: SharedPreferences would cache another process's old policy. */
internal object EarlyRootStore {
    private fun file(context: Context, name: String) = File(
        context.createDeviceProtectedStorageContext().filesDir, "early-root-$name.json",
    )

    private fun read(context: Context, name: String): JSONObject? {
        val target = file(context, name)
        if (!target.exists()) return null
        require(target.length() in 1..32_768) { "Invalid early root record" }
        return JSONObject(target.readText())
    }

    private fun write(context: Context, name: String, value: JSONObject) {
        val target = file(context, name)
        val directory = target.parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("early-root-", ".tmp", directory)
        try {
            temporary.outputStream().use {
                it.write(value.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun config(context: Context): EarlyRootConfig? = read(context, "config")?.let(EarlyRootConfig::parse)
    fun requested(context: Context): Boolean = runCatching { config(context)?.requested == true }.getOrDefault(false)

    @Synchronized fun save(context: Context, config: EarlyRootConfig) = write(context, "config", config.json())

    @Synchronized fun disable(context: Context) {
        val current = config(context) ?: return
        save(context, current.copy(enabled = false))
    }

    /** The controller updates this snapshot whenever a relevant setting changes. */
    @Synchronized fun syncOptions(context: Context) {
        val current = config(context) ?: return
        save(context, current.copy(
            enabled = current.enabled && current.flavor == AppPreferences.kernelsuFlavor(context).id,
            rootOnBoot = AppPreferences.bootRootMode(context),
            settleSeconds = AppPreferences.autoRootSettleSeconds(context),
            disableModules = AppPreferences.disableKsuModules(context),
            wipeModules = AppPreferences.wipeModuleState(context),
            protectImages = AppPreferences.partitionReadOnlyMode(context),
        ))
    }

    fun result(context: Context): JSONObject? = runCatching { read(context, "result") }.getOrNull()

    /** Keep a failed or still-running native handoff until verification or a new kernel boot. */
    fun protectsStaging(context: Context): Boolean {
        val boot = kernelBootToken() ?: return false
        val result = result(context) ?: return false
        return result.optString("boot") == boot && result.optString("state") in setOf("running", "failed") &&
            runCatching { DfBootAttempt.claimed(context, boot) }.getOrDefault(true)
    }

    @Synchronized fun record(context: Context, token: String, state: String, detail: String,
        startedLocked: Boolean, finishedLocked: Boolean = false, finalized: Boolean = false) {
        write(context, "result", JSONObject().put("boot", token).put("state", state)
            .put("detail", detail.take(1600)).put("startedLocked", startedLocked)
            .put("finishedLocked", finishedLocked).put("uptimeMs", BootSettle.elapsedMillis())
            .put("finalized", finalized))
    }

    @Synchronized fun finalized(context: Context) {
        val current = result(context) ?: return
        write(context, "result", current.put("finalized", true))
    }
}
