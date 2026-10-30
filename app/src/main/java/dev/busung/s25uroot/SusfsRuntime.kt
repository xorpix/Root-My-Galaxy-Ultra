package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

internal data class SusfsOutcome(val status: SusfsStatus, val report: String, val needsGrant: Boolean = false)
internal typealias SusfsShell = (String, Long) -> ShizukuController.ShellResult?

/** Manual boot-local activation: verified bundled bytes, root loader, then live ABI status. */
internal object SusfsRuntime {
    private const val SHARED_MODULE_DIR = "bzig"
    private val lock = Any()

    private fun profile(context: Context, variantDir: String): JSONObject =
        context.assets.open("susfs/$variantDir/profile.json").bufferedReader().use { JSONObject(it.readText()) }

    fun compatibilityIssue(context: Context): String? {
        val target = runCatching { susfsTargetFor(DeviceSnapshot.current()) }.getOrNull()
        if (target is SusfsTarget.Unsupported) return target.reason
        if (target == null) return "This device could not be read."
        return try {
            profile(context, (target as? SusfsTarget.Known)?.variantDir ?: SHARED_MODULE_DIR)
            null
        } catch (e: Exception) {
            "The bundled SusFS profile could not be read."
        }
    }

    private fun nativeTool(context: Context, profile: JSONObject, key: String): File {
        val entry = profile.getJSONObject(key)
        val file = File(context.applicationInfo.nativeLibraryDir, entry.getString("file"))
        check(file.isFile && file.canExecute()) { "The $key helper is missing or cannot execute." }
        check(sha256Hex(file.readBytes()) == entry.getString("sha256")) { "The $key helper failed its integrity check." }
        return file
    }

    private fun module(context: Context, profile: JSONObject): File {
        val entry = profile.getJSONObject("module")
        // Single shared binary: it carries a generic vermagic and the loader
        // rewrites it to the running kernel's required value (see SHARED_MODULE_DIR).
        val bytes = context.assets.open("susfs/$SHARED_MODULE_DIR/" + entry.getString("file")).use { it.readBytes() }
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

    private fun readStatus(context: Context, profile: JSONObject, shell: SusfsShell): SusfsOutcome {
        val tool = nativeTool(context, profile, "status")
        val result = shell(shellQuote(tool.path), 15)
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

    /** A read-only recovery check after a worker was interrupted; never attempts another load. */
    fun query(context: Context, snapshot: DeviceSnapshot = DeviceSnapshot.current(),
        shell: SusfsShell = KernelSuRuntime::rootShell): SusfsOutcome = runCatching {
        val target = susfsTargetFor(snapshot)
        if (target is SusfsTarget.Unsupported) return unavailable(target.reason)
        readStatus(context, profile(context, (target as? SusfsTarget.Known)?.variantDir ?: SHARED_MODULE_DIR), shell)
    }.getOrElse { unavailable(it.message ?: "SusFS status could not be read.") }

    fun activate(context: Context, snapshot: DeviceSnapshot = DeviceSnapshot.current(),
        shell: SusfsShell = KernelSuRuntime::rootShell): SusfsOutcome = synchronized(lock) {
        runCatching {
            // The UI and Direct Boot worker are different processes. Serialize the check/load pair.
            val directory = context.createDeviceProtectedStorageContext().filesDir
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, "susfs-activation.lock"), "rw").use { file ->
                file.channel.lock().use { activateLocked(context, snapshot, shell) }
            }
        }.getOrElse { unavailable(it.message ?: "SusFS activation failed.") }
    }

    private fun activateLocked(context: Context, snapshot: DeviceSnapshot, shell: SusfsShell): SusfsOutcome {
        val target = susfsTargetFor(snapshot)
        if (target is SusfsTarget.Unsupported) return unavailable(target.reason)
        val variantDir = (target as? SusfsTarget.Known)?.variantDir ?: SHARED_MODULE_DIR
        val profile = profile(context, variantDir)
        val root = shell("id; id -Z; uname -r", 15)
        if (!isRootAnswer(root)) return unavailable("Grant this app root permission in your manager, then retry.", needsGrant = true)
        // A module may now spoof uname. A live or partial ABI must prevent another load first.
        val before = readStatus(context, profile, shell)
        if (before.status.state != SusfsState.Absent) return before
        val runningRelease = root?.output?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.contains("android") }.orEmpty()
        // Family kernels use the shared bytes; the live feature check proves activation.
        if (target is SusfsTarget.Known) {
            val expected = profile.getString("kernelRelease")
            if (runningRelease != expected)
                return unavailable("The kernel reported by the root shell does not match ${target.label} ($expected). ${root?.output.orEmpty()}")
        }
        val rootContext = root?.output?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.matches(Regex("u:r:[a-zA-Z0-9_]+:s0")) }
            ?: return unavailable("The root shell's SELinux context could not be read. ${root?.output.orEmpty()}")
        val loader = nativeTool(context, profile, "loader")
        val module = module(context, profile)
        // Uname remains genuine until a user explicitly configures a spoof through the ABI.
        val command = "${shellQuote(loader.path)} ${shellQuote(module.path)} " +
            "uname_spoof_enabled=0 hide_modules=kernelsu,susfs_guard_lkm " +
            "su_ctx=${shellQuote(rootContext)} or_su_ctx=${shellQuote(rootContext)} avc_su_ctx=${shellQuote(rootContext)}"
        val loaded = shell(command, 60)
            ?: return unavailable("The loader did not finish. Refresh status before retrying.")
        // Query even on a nonzero load exit; never retry a live/partial module blindly.
        val after = readStatus(context, profile, shell)
        val report = "Device: ${snapshot.kernelVersionFull}\n" +
            "Loader exit: ${loaded.exitCode}\n${loaded.output}\nStatus:\n${after.report}"
        File(context.filesDir, "susfs-last-report.txt").writeText(report)
        val status = if (after.status.state == SusfsState.Absent) after.status.copy(
            detail = "SusFS is not active after the load attempt (loader exit ${loaded.exitCode}). See the report.",
        ) else after.status
        return after.copy(status = status, report = report)
    }
}
