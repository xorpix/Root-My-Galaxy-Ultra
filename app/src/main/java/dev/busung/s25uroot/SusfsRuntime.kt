package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

internal data class SusfsOutcome(val status: SusfsStatus, val report: String, val needsGrant: Boolean = false)
internal typealias SusfsShell = (String, Long) -> ShizukuController.ShellResult?

/** Retry an incomplete read once; never convert an unanswered/partial first
 * observation into permission to load another module. Only a verified reply
 * replaces it. There is no load or configuration in this helper. */
internal fun readSusfsStatus(command: String, shell: SusfsShell): SusfsOutcome {
    val first = shell(command, 15)
    val initial = first?.let { SusfsOutcome(parseSusfsStatus(it.output, it.exitCode), it.output) }
        ?: SusfsOutcome(SusfsStatus(SusfsState.Unavailable,
            detail = "The status check did not answer; retry activation before rebooting."),
            "The status helper did not answer.")
    if (initial.status.state == SusfsState.Absent || initial.status.abiVerified) return initial
    val retry = shell(command, 15) ?: return initial
    val parsed = parseSusfsStatus(retry.output, retry.exitCode)
    return if (parsed.abiVerified) SusfsOutcome(parsed, retry.output) else initial
}

/** One canonical, atomic report for foreground, early boot and already-loaded
 * activation. Readers must never observe a file truncated during replacement. */
internal fun writeSusfsActivationReport(directory: File, header: String, outcome: SusfsOutcome) {
    check(directory.isDirectory || directory.mkdirs())
    val temporary = File.createTempFile("susfs-report-", ".tmp", directory)
    try {
        temporary.writeText(header + "State: ${outcome.status.state}\nDetails: ${outcome.status.detail}\n" + outcome.report + "\n")
        check(temporary.renameTo(File(directory, "susfs-last-report.txt"))) { "Could not replace the SusFS report." }
    } finally { temporary.delete() }
}

/** Manual boot-local activation: verified bundled bytes, root loader, then live ABI status. */
internal object SusfsRuntime {
    // Exact firmware profiles share one verified module asset.
    private const val SHARED_MODULE_DIR = "bzig"
    private val lock = Any()

    private fun profile(context: Context, variantDir: String): JSONObject =
        context.assets.open("susfs/$variantDir/profile.json").bufferedReader().use { JSONObject(it.readText()) }

    fun compatibilityIssue(context: Context): String? {
        val target = runCatching { susfsTargetFor(DeviceSnapshot.current()) }.getOrNull()
        if (target is SusfsTarget.Unsupported) return target.reason
        if (target == null) return "This device could not be read."
        return try {
            profile(context, (target as SusfsTarget.Known).variantDir)
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

    internal fun priorityTool(context: Context): File =
        nativeTool(context, profile(context, SHARED_MODULE_DIR), "priorityControl")

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
        return readSusfsStatus(shellQuote(tool.path), shell)
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
        readStatus(context, profile(context, (target as SusfsTarget.Known).variantDir), shell)
    }.getOrElse { unavailable(it.message ?: "SusFS status could not be read.") }

    fun activate(context: Context, snapshot: DeviceSnapshot = DeviceSnapshot.current(),
        shell: SusfsShell = KernelSuRuntime::rootShell): SusfsOutcome = synchronized(lock) {
        runCatching {
            // The UI and Direct Boot worker are different processes. Serialize the check/load pair.
            val directory = context.createDeviceProtectedStorageContext().filesDir
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, "susfs-activation.lock"), "rw").use { file ->
                file.channel.lock().use {
                    val outcome = runCatching { activateLocked(context, snapshot, shell) }
                        .getOrElse { unavailable(it.message ?: "SusFS activation failed.") }
                    runCatching {
                        writeSusfsActivationReport(directory,
                            "App: ${BuildConfig.VERSION_NAME}\nBoot: ${AutoRootSupport.currentBootToken() ?: "unknown"}\n", outcome)
                    }
                    outcome
                }
            }
        }.getOrElse { unavailable(it.message ?: "SusFS activation failed.") }
    }

    private fun activateLocked(context: Context, snapshot: DeviceSnapshot, shell: SusfsShell): SusfsOutcome {
        val target = susfsTargetFor(snapshot)
        if (target is SusfsTarget.Unsupported) return unavailable(target.reason)
        val variantDir = (target as SusfsTarget.Known).variantDir
        val profile = profile(context, variantDir)
        val root = shell("id; id -Z; uname -r", 15)
        if (!isRootAnswer(root)) return unavailable("Grant this app root permission in your manager, then retry.", needsGrant = true)
        // A module may now spoof uname. A live or partial ABI must prevent another load first.
        val before = readStatus(context, profile, shell)
        if (before.status.state != SusfsState.Absent) return SusfsAutoModules.attach(
            SusfsAutoApps.attach(context, before, shell), shell,
            context.createDeviceProtectedStorageContext().cacheDir.absolutePath,
        )
        val runningRelease = root?.output?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.contains("android") }.orEmpty()
        // Recheck the exact qualified release in the root shell before loading.
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
        // Installer modules can stay discoverable to root through the native policy.
        val command = "${shellQuote(loader.path)} ${shellQuote(module.path)} " +
            "uname_spoof_enabled=0 hide_modules=kernelsu,susfs_guard_lkm,nomount " +
            "hide_modules_root_visible=nomount " +
            "priority_auto_root=1 priority_appid=${context.applicationInfo.uid % 100000} " +
            "su_ctx=${shellQuote(rootContext)} or_su_ctx=${shellQuote(rootContext)} avc_su_ctx=${shellQuote(rootContext)}"
        val loaded = shell(command, 60)
            ?: return unavailable("The loader did not finish. Refresh status before retrying.")
        // Query even on a nonzero load exit; never retry a live/partial module blindly.
        val after = readStatus(context, profile, shell)
        val report = "Device: ${snapshot.kernelVersionFull}\n" +
            "Loader exit: ${loaded.exitCode}\n${loaded.output}\nStatus:\n${after.report}"
        val status = if (after.status.state == SusfsState.Absent) after.status.copy(
            detail = "SusFS is not active after the load attempt (loader exit ${loaded.exitCode}). See the report.",
        ) else after.status
        val configured = SusfsAutoModules.attach(
            SusfsAutoApps.attach(context, after.copy(status = status, report = report), shell), shell,
            context.createDeviceProtectedStorageContext().cacheDir.absolutePath,
        )
        return configured
    }
}

