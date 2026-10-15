package dev.busung.s25uroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.busung.s25uroot.d2.setup.SetupCommand
import dev.experimental.rmgu.d2.D2Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class D2Status(
    val installed: Boolean = false,
    val controllable: Boolean = false,
    val needsUpdate: Boolean = false,
    val activated: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val current: Boolean = false,
    val verified: Boolean = false,
    val lock: Int = -1,
    val maintenance: Int = -1,
    val at: Int = -1,
    val ageMs: Long = -1,
    val detail: String = "",
)

internal data class D2SetupResult(val ok: Boolean, val committed: Boolean, val detail: String)

/** The companion is authenticated before any IPC, including read-only status requests. */
internal object D2Helper {
    private val uri = Uri.parse("content://${D2Protocol.AUTHORITY}")
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

    private fun status(context: Context, method: String, enabled: Boolean? = null): D2Status {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(D2Protocol.HELPER, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) { return D2Status() }
        val app = requireNotNull(info.applicationInfo)
        if (app.uid != 1000 || app.processName != "system" ||
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
            pm.checkSignatures(context.packageName, D2Protocol.HELPER) != PackageManager.SIGNATURE_MATCH
        ) return D2Status(installed = true, detail = "Helper identity is not supported; no control request was sent")
        val expected = metadata(context)
        // First inspect the protocol. An older matching helper may be disabled, but cannot be newly enabled.
        val reply = context.contentResolver.call(uri, "status", null, null)
            ?: error("Helper did not return a status")
        require(reply.getInt("api") == D2Protocol.API && reply.getInt("uid") == 1000) {
            "Unsupported helper protocol or UID"
        }
        val needsUpdate = reply.getString("source") != expected.getString("source") ||
            reply.getInt("version") != expected.getInt("version")
        require(method != "configure" || enabled != true || !needsUpdate) { "Update the helper before enabling D2" }
        val result = if (method == "status") reply else context.contentResolver.call(
            uri, method, null, enabled?.let { Bundle().apply { putBoolean("enabled", it) } },
        ) ?: error("Helper did not return a result")
        val timeout = result.getBoolean("timed_out")
        return D2Status(
            installed = true, controllable = true, needsUpdate = needsUpdate,
            activated = result.getBoolean("activated") && app.flags and ApplicationInfo.FLAG_STOPPED == 0,
            enabled = result.getBoolean("enabled"), busy = result.getBoolean("busy"),
            current = result.getBoolean("current"), verified = result.getBoolean("verified") && !timeout,
            lock = result.getInt("lock", -1), maintenance = result.getInt("maintenance", -1),
            at = result.getInt("at", -1), ageMs = result.getLong("age_ms", -1),
            detail = if (timeout) "DMC check timed out; the flag is unconfirmed" else result.getString("detail").orEmpty(),
        )
    }

    suspend fun query(context: Context, refresh: Boolean = false, enabled: Boolean? = null): D2Status =
        withContext(Dispatchers.IO) {
            var result = status(context, if (enabled != null) "configure" else if (refresh) "refresh" else "status", enabled)
            // The provider returns immediately; secure-service calls run on the companion's bounded worker.
            repeat(9) {
                if (!result.busy) return@withContext result
                delay(1000)
                result = status(context, "status")
            }
            result
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
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Embedded helper is not a valid APK")
        val app = requireNotNull(archive.applicationInfo)
        require(archive.packageName == D2Protocol.HELPER && archive.sharedUserId == "android.uid.system" &&
            app.processName == "system" && app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            archive.longVersionCode == expected.getLong("version") &&
            archive.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()?.contentEquals(currentSigner(context)) == true
        ) { "Embedded helper identity check failed" }
        Intent(Intent.ACTION_VIEW).setDataAndType(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk),
            "application/vnd.android.package-archive",
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun activation() = Intent().setComponent(ComponentName(D2Protocol.HELPER, D2Protocol.ACTIVATION))

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

    suspend fun setup(context: Context, mode: String): D2SetupResult = withContext(Dispatchers.IO) {
        val command = SetupCommand.command(context.applicationInfo.sourceDir, mode, hex(currentSigner(context)))
        // If the UI process is lost after a commit, the next launch must still ask for a reboot.
        val marking = mode != "inspect" && !rebootPending(context)
        if (marking) check(rememberBoot(context, kernelBootToken()?.let { "kernel-$it" } ?: "unknown")) {
            "The setup recovery marker could not be saved; no system change was requested"
        }
        val shell = KernelSuRuntime.rootShell(command, timeoutSeconds = 75)
            ?: run {
                if (marking) preferences(context).edit().remove("prepared_boot").commit()
                return@withContext D2SetupResult(false, false, context.getString(R.string.d2_root_required))
            }
        val json = shell.output.lineSequence().lastOrNull { it.startsWith("RMGU_D2_SETUP_RESULT ") }
            ?.substringAfter("RMGU_D2_SETUP_RESULT ")?.let(::JSONObject)
            ?: return@withContext D2SetupResult(false, false, "Setup did not return a verified result. Check root permission, then use Inspect setup before retrying.")
        val ok = shell.exitCode == 0 && json.optBoolean("ok")
        val committed = json.optBoolean("committed")
        if (mode == "prepare" && !ok && !committed && marking) {
            preferences(context).edit().remove("prepared_boot").commit()
        }
        if ((mode == "prepare" || mode == "remove") && (ok || committed)) {
            rememberBoot(context, json.optString("boot_key"))
        }
        D2SetupResult(ok, committed, json.optString(if (ok) "status" else "error", "Setup did not complete"))
    }
}
