package dev.busung.s25uroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.busung.s25uroot.d2.setup.SetupCommand
import dev.experimental.rmgu.d2.D2Protocol
import dev.experimental.rmgu.d2.KernelIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class D2Status(
    val installed: Boolean = false,
    val identityVerified: Boolean = false,
    val controllable: Boolean = false,
    val needsUpdate: Boolean = false,
    val needsReboot: Boolean = false,
    val activated: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val current: Boolean = false,
    val verified: Boolean = false,
    val earlyRootEnabled: Boolean = false,
    val kernelRelease: String? = null,
)

internal data class D2SetupResult(val ok: Boolean, val committed: Boolean, val rootRequired: Boolean = false)

/** The companion is authenticated before any IPC, including read-only status requests. */
internal object D2Helper {
    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("d2_setup", Context.MODE_PRIVATE)

    private fun metadata(context: Context) = JSONObject(
        context.assets.open(D2Protocol.METADATA).bufferedReader().use { it.readText() },
    ).also {
        require(it.getInt("api") == D2Protocol.API && it.getString("package") == D2Protocol.HELPER)
        require(it.getString("source").matches(Regex("[0-9a-f]{64}")))
    }

    private fun currentSigner(context: Context): ByteArray {
        val signers = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        ).signingInfo?.apkContentsSigners ?: error("Controller signing identity is unavailable")
        require(signers.size == 1) { "Multiple controller signers are unsupported" }
        return signers.single().toByteArray()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Installed(val status: D2Status, val source: String = "", val version: Int = 0, val stopped: Boolean = false)

    private fun installed(context: Context): Installed {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(D2Protocol.HELPER, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) { return Installed(D2Status()) }
        val app = requireNotNull(info.applicationInfo)
        if (app.uid != 1000 || app.processName != "system" ||
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
            pm.checkSignatures(context.packageName, D2Protocol.HELPER) != PackageManager.SIGNATURE_MATCH
        ) return Installed(D2Status(installed = true))
        val expected = metadata(context)
        val source = expected.getString("source")
        val version = expected.getInt("version")
        val needsUpdate = app.metaData?.getString(D2Protocol.SOURCE_METADATA) != source || info.longVersionCode != version.toLong()
        // Do not instantiate a newly added component from the system process's old cached APK.
        val needsReboot = !needsUpdate && (helperInstallPending(context, source) ||
            info.lastUpdateTime > System.currentTimeMillis() - SystemClock.elapsedRealtime() + 2000)
        return Installed(D2Status(installed = true, identityVerified = true, needsUpdate = needsUpdate,
            needsReboot = needsReboot), source, version, app.flags and ApplicationInfo.FLAG_STOPPED != 0)
    }

    private suspend fun status(installed: Installed, client: D2ControlClient, method: String, enabled: Boolean? = null): D2Status {
        suspend fun call(operation: String, value: Boolean? = null): Bundle {
            val result = client.call(operation, installed.source, installed.version, value)
            require(result.getInt("api") == D2Protocol.API && result.getInt("uid") == 1000) {
                "Unsupported helper protocol or UID"
            }
            result.getString("error")?.let { error(it) }
            return result
        }
        val reply = call("status")
        val needsReboot = reply.getString("source") != installed.source || reply.getInt("version") != installed.version
        require(method !in setOf("configure", "configure_root") || enabled != true || !needsReboot) {
            "Install the matching helper and fully reboot before enabling D2"
        }
        val result = if (method == "status" || (needsReboot && !(method in setOf("configure", "configure_root") && enabled == false))) reply
            else call(method, enabled)
        val timeout = result.getBoolean("timed_out")
        return installed.status.copy(
            controllable = true, needsReboot = needsReboot,
            activated = result.getBoolean("activated") && !installed.stopped,
            enabled = result.getBoolean("enabled"), busy = result.getBoolean("busy"),
            current = result.getBoolean("current"), verified = result.getBoolean("verified") && !timeout,
            earlyRootEnabled = result.getBoolean("early_root_enabled"),
            kernelRelease = result.getString("kernel_release")?.takeIf { !needsReboot && KernelIdentity.valid(it) },
        )
    }

    suspend fun setEarlyRoot(context: Context, enabled: Boolean): D2Status = withContext(Dispatchers.IO) {
        val installed = installed(context)
        check(installed.status.identityVerified && !installed.status.needsUpdate && !installed.status.needsReboot) {
            "Update the boot helper in Recovery Management, fully reboot, then activate it"
        }
        D2ControlClient.use(context) { status(installed, it, "configure_root", enabled) }.also {
            check(it.earlyRootEnabled == enabled) { "The helper did not confirm the early root setting" }
        }
    }

    suspend fun query(context: Context, refresh: Boolean = false, enabled: Boolean? = null): D2Status =
        withContext(Dispatchers.IO) {
            val installed = installed(context)
            val base = installed.status
            if (!base.identityVerified || base.needsUpdate || base.needsReboot) {
                check(enabled == null) { "Update the embedded helper and fully reboot before changing D2" }
                return@withContext base
            }
            try {
                D2ControlClient.use(context) { client ->
                    var result = status(installed, client, if (enabled != null) "configure" else if (refresh) "refresh" else "status", enabled)
                    // IPC never waits for a Samsung vault call; that work has its own bounded worker.
                    repeat(9) {
                        if (!result.busy || result.needsReboot) return@use result
                        delay(1000)
                        result = status(installed, client, "status")
                    }
                    result
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (enabled != null) throw error
                // Retain the authenticated installed identity so Update/Activate remain reachable.
                base
            }
        }

    private fun helperInstallPending(context: Context, source: String): Boolean {
        val p = preferences(context)
        if (p.getString("helper_source", null) != source) return false
        val boot = kernelBootToken()
        return if (boot != null) p.getString("helper_boot", null) == boot else
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) == p.getInt("helper_count", -2)
    }

    suspend fun installer(context: Context): Intent = withContext(Dispatchers.IO) {
        val expected = metadata(context)
        val bytes = context.assets.open(D2Protocol.ASSET).use { it.readBytes() }
        require(bytes.size.toLong() == expected.getLong("size") &&
            hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == expected.getString("sha256")) {
            "Embedded helper integrity check failed"
        }
        val directory = File(context.cacheDir, "d2-helper").apply { mkdirs() }
        val apk = File(directory, "helper.apk").apply { writeBytes(bytes) }
        val archive = context.packageManager.getPackageArchiveInfo(
            apk.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA,
        )
            ?: error("Embedded helper is not a valid APK")
        val app = requireNotNull(archive.applicationInfo)
        require(archive.packageName == D2Protocol.HELPER && archive.sharedUserId == "android.uid.system" &&
            app.processName == "system" && app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            archive.longVersionCode == expected.getLong("version") &&
            app.metaData?.getString(D2Protocol.SOURCE_METADATA) == expected.getString("source") &&
            archive.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()?.contentEquals(currentSigner(context)) == true
        ) { "Embedded helper identity check failed" }
        check(preferences(context).edit().putString("helper_source", expected.getString("source"))
            .putString("helper_boot", kernelBootToken()).putInt("helper_count", Settings.Global.getInt(
                context.contentResolver, Settings.Global.BOOT_COUNT, -1,
            )).commit()) { "The helper reboot marker could not be saved; installation was not started" }
        Intent(Intent.ACTION_VIEW).setDataAndType(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk),
            "application/vnd.android.package-archive",
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun activation(context: Context): Intent = withContext(Dispatchers.IO) {
        val status = installed(context).status
        check(status.identityVerified && !status.needsUpdate && !status.needsReboot && !rebootPending(context)) {
            "Update the embedded helper and fully reboot before activating D2"
        }
        Intent().setComponent(ComponentName(D2Protocol.HELPER, D2Protocol.ACTIVATION))
    }

    /** A successful preparation is remembered until a different kernel boot, including after an app restart. */
    fun rebootPending(context: Context): Boolean = runCatching {
        val p = preferences(context)
        val prepared = p.getString("prepared_boot", null) ?: return@runCatching false
        val current = kernelBootToken()?.let { "kernel-$it" }
        if (current != null) current == prepared else
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) == p.getInt("prepared_count", -1)
    }.getOrDefault(true)

    private fun rememberBoot(context: Context, key: String) = preferences(context).edit()
        .putString("prepared_boot", key)
        .putInt("prepared_count", runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)).commit()

    fun prepared(context: Context): Boolean = preferences(context).getBoolean("prepared", false)

    suspend fun setup(context: Context, mode: String): D2SetupResult = withContext(Dispatchers.IO) {
        val command = SetupCommand.command(context.applicationInfo.sourceDir, mode, hex(currentSigner(context)))
        // If the UI process is lost after a commit, the next launch must still ask for a reboot.
        val marking = !rebootPending(context)
        if (marking) check(rememberBoot(context, kernelBootToken()?.let { "kernel-$it" } ?: "unknown")) {
            "The setup recovery marker could not be saved; no system change was requested"
        }
        val shell = KernelSuRuntime.rootShell(command, timeoutSeconds = 75)
            ?: run {
                if (marking) preferences(context).edit().remove("prepared_boot").commit()
                return@withContext D2SetupResult(false, false, rootRequired = true)
            }
        val json = shell.output.lineSequence().lastOrNull { it.startsWith("RMGU_D2_SETUP_RESULT ") }
            ?.substringAfter("RMGU_D2_SETUP_RESULT ")?.let(::JSONObject)
            ?: return@withContext D2SetupResult(false, false)
        val ok = shell.exitCode == 0 && json.optBoolean("ok")
        val committed = json.optBoolean("committed")
        if (mode == "prepare" && !ok && !committed && marking) {
            preferences(context).edit().remove("prepared_boot").commit()
        }
        if ((mode == "prepare" || mode == "remove") && (ok || committed)) {
            check(preferences(context).edit().putBoolean("prepared", mode == "prepare").commit()) {
                "The setup progress could not be saved; restart before continuing"
            }
            rememberBoot(context, json.optString("boot_key"))
        }
        D2SetupResult(ok, committed)
    }
}
