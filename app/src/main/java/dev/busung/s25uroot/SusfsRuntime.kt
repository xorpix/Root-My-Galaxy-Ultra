package dev.busung.s25uroot

import android.content.Context
import java.io.File
import org.json.JSONObject

internal data class SusfsOutcome(val status: SusfsStatus, val report: String, val needsGrant: Boolean = false)

/** Manual boot-local activation: verified bundled bytes, root loader, then live ABI status. */
internal object SusfsRuntime {
    private const val PROFILE = "susfs/bzig/profile.json"
    private val lock = Any()

    private fun profile(context: Context): JSONObject =
        context.assets.open(PROFILE).bufferedReader().use { JSONObject(it.readText()) }

    fun compatibilityIssue(context: Context): String? = runCatching {
        susfsCompatibilityIssue(DeviceSnapshot.current(), profile(context).getString("kernelRelease"))
    }.getOrElse { "The bundled SusFS profile could not be read." }

    private fun nativeTool(context: Context, profile: JSONObject, key: String): File {
        val entry = profile.getJSONObject(key)
        val file = File(context.applicationInfo.nativeLibraryDir, entry.getString("file"))
        check(file.isFile && file.canExecute()) { "The $key helper is missing or cannot execute." }
        check(sha256Hex(file.readBytes()) == entry.getString("sha256")) { "The $key helper failed its integrity check." }
        return file
    }

    private fun module(context: Context, profile: JSONObject): File {
        val entry = profile.getJSONObject("module")
        val bytes = context.assets.open("susfs/bzig/" + entry.getString("file")).use { it.readBytes() }
        check(sha256Hex(bytes) == entry.getString("sha256")) { "The SusFS module failed its integrity check." }
        val directory = File(context.filesDir, "susfs").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, entry.getString("file"))
        val temporary = File(directory, "module.tmp")
        temporary.outputStream().use { it.write(bytes); it.flush() }
        check(temporary.renameTo(file)) { "The verified module could not be staged." }
        check(sha256Hex(file.readBytes()) == entry.getString("sha256")) { "The staged module failed its integrity check." }
        return file
    }

    private fun unavailable(detail: String, needsGrant: Boolean = false) = SusfsOutcome(
        SusfsStatus(SusfsState.Unavailable, detail = detail), detail, needsGrant,
    )

    private fun readStatus(context: Context, profile: JSONObject): SusfsOutcome {
        val tool = nativeTool(context, profile, "status")
        val result = KernelSuRuntime.rootShell(shellQuote(tool.path), timeoutSeconds = 15)
            // No grant claim here: inside activation the daemon was just proven
            // up, so a null is transient — only the entry check below, made
            // with a live daemon behind it, means "this UID is refused".
            ?: return unavailable("The status check did not answer; retry once before rebooting.")
        val status = parseSusfsStatus(result.output, result.exitCode)
        return SusfsOutcome(status, result.output)
    }

    /** Last activation result, recorded for the status row. Never throws. */
    fun recordActivation(context: Context, status: SusfsStatus) {
        runCatching {
            AppPreferences.recordSusfsActivation(
                context,
                state = if (status.state == SusfsState.Active) "active" else "other",
                version = status.version,
                bootToken = AutoRootSupport.currentBootToken(),
            )
        }
    }

    fun activate(context: Context): SusfsOutcome = synchronized(lock) {
        runCatching {
            val profile = profile(context)
            val issue = susfsCompatibilityIssue(DeviceSnapshot.current(), profile.getString("kernelRelease"))
            if (issue != null) return@synchronized unavailable(issue)
            val root = KernelSuRuntime.rootShell("id; id -Z; uname -r", timeoutSeconds = 15)
            if (!isRootAnswer(root)) return@synchronized unavailable("Grant this app root permission in your manager, then retry.", needsGrant = true)
            if (root?.output?.lineSequence()?.none { it.trim() == profile.getString("kernelRelease") } != false)
                return@synchronized unavailable("The kernel reported by the root shell does not match BZIG. ${root?.output.orEmpty()}")
            val rootContext = root?.output?.lineSequence()?.map { it.trim() }
                ?.firstOrNull { it.matches(Regex("u:r:[a-zA-Z0-9_]+:s0")) }
                ?: return@synchronized unavailable("The root shell's SELinux context could not be read. ${root?.output.orEmpty()}")
            val before = readStatus(context, profile)
            if (before.status.state != SusfsState.Absent) return@synchronized before
            val loader = nativeTool(context, profile, "loader")
            val module = module(context, profile)
            // Uname remains genuine until a user explicitly configures a spoof through the ABI.
            val command = "${shellQuote(loader.path)} ${shellQuote(module.path)} " +
                "uname_spoof_enabled=0 hide_modules=kernelsu,susfs_guard_lkm " +
                "su_ctx=${shellQuote(rootContext)} or_su_ctx=${shellQuote(rootContext)} avc_su_ctx=${shellQuote(rootContext)}"
            val loaded = KernelSuRuntime.rootShell(command, timeoutSeconds = 60)
                ?: return@synchronized unavailable("The loader did not finish. Refresh status before retrying.")
            // Query even on a nonzero load exit; never retry a live/partial module blindly.
            val after = readStatus(context, profile)
            val report = "Device: ${DeviceSnapshot.current().kernelVersionFull}\n" +
                "Loader exit: ${loaded.exitCode}\n${loaded.output}\nStatus:\n${after.report}"
            File(context.filesDir, "susfs-last-report.txt").writeText(report)
            val status = if (after.status.state == SusfsState.Absent) after.status.copy(
                detail = "SusFS is not active after the load attempt (loader exit ${loaded.exitCode}). See the report.",
            ) else after.status
            after.copy(status = status, report = report)
        }.getOrElse { unavailable(it.message ?: "SusFS activation failed.") }
    }
}
