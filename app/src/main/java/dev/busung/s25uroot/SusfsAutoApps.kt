package dev.busung.s25uroot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PersistableBundle
import android.os.UserManager
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipFile
import org.json.JSONObject

internal data class SusfsInstalledApp(val name: String, val uid: Int)
internal data class SusfsAutomaticStatus(
    val ready: Boolean, val automatic: Boolean, val ownAppId: Int, val rootLookup: Boolean,
) {
    fun protects(appId: Int) = ready && automatic && rootLookup && ownAppId == appId
}

private val rootManagerPackages = setOf(
    "me.weishu.kernelsu", "com.rifsxd.ksunext", "org.bakasu.bakasu",
    "com.resukisu.resukisu", "com.kowx712.supermanager", "com.sukisu.ultra",
    "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.bmax.apatch",
    "org.lsposed.manager", "org.meowcat.edxposed.manager", "moe.shizuku.manager",
)
private val rootPackageName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")

/** Read APK metadata only: spoofing names/labels or hiding the launcher is irrelevant.
 * Newer managers may keep native libraries in a base or ABI split APK instead of
 * extracting them. Never execute or unpack another app's daemon to recognize it.
 */
internal fun susfsHasManagerDaemon(nativeDir: File?, apks: List<File>, abis: List<String>): Boolean {
    if (nativeDir != null && runCatching { File(nativeDir, "libksud.so").isFile }.getOrDefault(false)) return true
    for (apk in apks.distinct()) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("SusFS app scan stopped")
        val found = runCatching {
            ZipFile(apk).use { zip ->
                abis.any { abi -> zip.getEntry("lib/$abi/libksud.so")?.let { !it.isDirectory && it.size > 0 } == true }
            }
        }.getOrDefault(false)
        if (found) return true
    }
    return false
}

/** Extras are freshly resolved names; live root grants are handled in the kernel. */
internal fun susfsAutomaticManagers(apps: List<SusfsInstalledApp>, discovered: Set<String>): List<String> =
    apps.asSequence().filter { it.uid in 10000..19999 }
        .filter { it.name.length <= 255 && rootPackageName.matches(it.name) }
        .filter { it.name in rootManagerPackages || it.name in discovered }
        .map { it.name }.distinct().sorted().toList()

internal fun susfsCommandSucceeded(result: ShizukuController.ShellResult?): Boolean = runCatching {
    if (result == null || result.exitCode != 0) return false
    val json = JSONObject(result.output.lineSequence().last { it.trim().startsWith("{") })
    json.getInt("schema") == 1 && json.getLong("syscall") == 0L && json.getInt("error") == 0
}.getOrDefault(false)

internal fun susfsConfirmedFailure(result: ShizukuController.ShellResult?): Boolean = runCatching {
    if (result == null || result.exitCode != 1) return false
    val json = JSONObject(result.output.lineSequence().last { it.trim().startsWith("{") })
    json.getInt("schema") == 1 && json.getLong("syscall") == 0L && json.getInt("error") < 0
}.getOrDefault(false)

internal fun parseSusfsAutomaticStatus(result: ShizukuController.ShellResult?): SusfsAutomaticStatus? = runCatching {
    if (result == null || result.exitCode != 0) return null
    val json = JSONObject(result.output.lineSequence().last { it.trim().startsWith("{") })
    require(json.getInt("schema") == 1)
    val fields = json.getString("status").trim().split(' ').associate {
        val pair = it.split('=', limit = 2); require(pair.size == 2); pair[0] to pair[1]
    }
    fun bit(key: String): Boolean { require(fields[key] in setOf("0", "1")); return fields[key] == "1" }
    SusfsAutomaticStatus(bit("ready"), bit("automatic"), fields.getValue("own_appid").toInt(), bit("root_lookup"))
}.getOrNull()

/** Automatic root policy is boot-local. Package inventory is deferred off the root path. */
internal object SusfsAutoApps {
    enum class State { Automatic, Unavailable }
    data class Record(val boot: String, val appId: Int, val version: String, val state: State)
    private const val JOB = 0x535546
    private val monitor = Any()
    private var watching = false

    private fun directory(context: Context) = context.createDeviceProtectedStorageContext().filesDir
    private fun appId(context: Context) = context.applicationInfo.uid % 100000
    private fun unlocked(context: Context) = context.getSystemService(UserManager::class.java).isUserUnlocked

    internal fun decodeRecord(text: String, boot: String?, ownId: Int): Record? = runCatching {
        if (boot == null) return null
        val json = JSONObject(text); require(json.getInt("schema") == 1)
        require(json.getString("boot") == boot && json.getInt("appId") == ownId)
        val version = json.getString("version"); require(version.matches(Regex("v?2\\.\\d+\\.\\d+")))
        Record(boot, ownId, version, State.valueOf(json.getString("state")))
    }.getOrNull()

    fun current(context: Context): Record? = runCatching {
        decodeRecord(File(directory(context), "susfs-auto-apps.json").readText(),
            AutoRootSupport.currentBootToken(), appId(context))
    }.getOrNull()

    private fun record(context: Context, version: String, state: State) {
        val boot = AutoRootSupport.currentBootToken() ?: return
        val dir = directory(context); check(dir.isDirectory || dir.mkdirs())
        val text = JSONObject().put("schema", 1).put("boot", boot).put("appId", appId(context))
            .put("version", version).put("state", state.name).toString()
        val target = File(dir, "susfs-auto-apps.json")
        if (runCatching { target.readText() }.getOrNull() == text) return
        val temporary = File.createTempFile("susfs-auto-", ".tmp", dir)
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray()); it.fd.sync() }
            check(temporary.renameTo(target))
        } finally { temporary.delete() }
    }

    private fun <T> locked(context: Context, block: () -> T): T = synchronized(monitor) {
        val dir = directory(context); check(dir.isDirectory || dir.mkdirs())
        RandomAccessFile(File(dir, "susfs-auto-apps.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    private fun ensureAutomatic(context: Context, shell: SusfsShell): Boolean {
        val tool = SusfsRuntime.priorityTool(context)
        val command = shellQuote(tool.path)
        val answer = shell("$command status", 8)
        val status = parseSusfsAutomaticStatus(answer)
        if (status?.protects(appId(context)) == true) return true
        if (status?.ready != true || !status.rootLookup) {
            AppLog.warn(AppLogTags.KERNEL_SU, "SusFS automatic selection status (exit ${answer?.exitCode}): ${answer?.output?.take(1024).orEmpty()}")
            return false
        }
        return susfsCommandSucceeded(shell("$command auto ${appId(context)}", 8))
    }

    fun attach(context: Context, core: SusfsOutcome, shell: SusfsShell): SusfsOutcome {
        if (core.status.state != SusfsState.Active) return core
        val automatic = runCatching {
            locked(context) {
                val enabled = ensureAutomatic(context, shell)
                record(context, core.status.version, if (enabled) State.Automatic else State.Unavailable)
                enabled
            }
        }.getOrElse {
            AppLog.warn(AppLogTags.KERNEL_SU, "Automatic SusFS app protection: ${it.message}")
            runCatching { locked(context) { record(context, core.status.version, State.Unavailable) } }
            false
        }
        if (automatic) request(context)
        val detail = if (automatic) "Root-related apps are selected automatically."
            else "Automatic app protection could not start. Core SusFS is active."
        return core.copy(status = core.status.copy(detail = core.status.detail + ". " + detail),
            report = core.report + "\nApp protection: " + detail)
    }

    private fun eligible(context: Context): Boolean {
        if (!unlocked(context)) return false
        if (current(context) != null) return true
        val boot = AutoRootSupport.currentBootToken() ?: return false
        return AppPreferences.susfsState(context) == "active" && AppPreferences.susfsBootToken(context) == boot
    }

    fun request(context: Context, attempt: Int = 0) = runCatching {
        if (!eligible(context)) return@runCatching
        val extras = PersistableBundle().apply { putInt("attempt", attempt) }
        val info = JobInfo.Builder(JOB, ComponentName(context, SusfsAutoAppsJob::class.java))
            .setMinimumLatency(if (attempt == 0) 2000 else 5000)
            .setOverrideDeadline(15000).setExtras(extras).build()
        context.getSystemService(JobScheduler::class.java).schedule(info)
    }.onFailure { AppLog.warn(AppLogTags.KERNEL_SU, "SusFS app refresh could not be scheduled: ${it.message}") }.let { Unit }

    /** Only background work enumerates installed APKs. No polling of root grants. */
    fun refresh(context: Context): Boolean {
        val ready = locked(context) {
            if (!eligible(context) || Thread.currentThread().isInterrupted) return@locked false
            val shell: SusfsShell = SuShell::run
            val version = current(context)?.version ?: run {
                val core = SusfsRuntime.query(context, shell = shell)
                if (core.status.state != SusfsState.Active) return@locked false
                core.status.version
            }
            val enabled = ensureAutomatic(context, shell)
            record(context, version, if (enabled) State.Automatic else State.Unavailable)
            enabled
        }
        if (!ready) return false
        // APK metadata can take time. Do not hold the configuration lock while
        // scanning: an activation must not wait behind installed-app inventory.
        val inventory = context.packageManager.getInstalledPackages(0)
        val packages = inventory.mapNotNull { p ->
            p.applicationInfo?.let { SusfsInstalledApp(p.packageName, it.uid) }
        }
        val discovered = KernelSuFlavor.entries.mapNotNull { AppPreferences.managerPackage(context, it) }.toMutableSet()
        for (installed in inventory) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("SusFS app scan stopped")
            val app = installed.applicationInfo ?: continue
            if (app.uid !in 10000..19999 || installed.packageName == context.packageName ||
                installed.packageName in rootManagerPackages || installed.packageName in discovered) continue
            val apks = (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()).map(::File)
            if (susfsHasManagerDaemon(app.nativeLibraryDir?.let(::File), apks, Build.SUPPORTED_ABIS.toList())) {
                discovered.add(installed.packageName)
            }
        }
        val names = susfsAutomaticManagers(packages, discovered).joinToString(",")
        check(names.length < 65535) { "Too many root managers for the SusFS package list" }
        return locked(context) {
            if (!eligible(context) || Thread.currentThread().isInterrupted) return@locked false
            val shell: SusfsShell = SuShell::run
            val command = shellQuote(SusfsRuntime.priorityTool(context).path)
            val result = shell(if (names.isEmpty()) "$command clear" else "$command set ${shellQuote(names)}", 10)
            if (susfsCommandSucceeded(result)) return@locked false
            // Clear only confirmed failed snapshots; an indeterminate timeout
            // must never start a competing fallback command.
            if (susfsConfirmedFailure(result)) {
                val cleared = shell("$command clear", 8)
                if (susfsCommandSucceeded(cleared)) return@locked true
            }
            AppLog.warn(AppLogTags.KERNEL_SU, "SusFS manager selection could not be refreshed")
            false
        }
    }

    fun watch(context: Context) {
        if (watching) return
        watching = true
        val receiver = SusfsAutoAppsReceiver()
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
    }
}

class SusfsAutoAppsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { SusfsAutoApps.request(context) }
}

class SusfsAutoAppsJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running: JobParameters? = null
    private var future: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean = synchronized(this) {
        running = params
        future = executor.submit {
            val retry = runCatching { SusfsAutoApps.refresh(this) }.getOrElse {
                AppLog.warn(AppLogTags.KERNEL_SU, "SusFS app selection: ${it.message}"); false
            }
            synchronized(this) {
                if (running === params) {
                    running = null
                    jobFinished(params, false)
                    if (retry && params.extras.getInt("attempt", 0) == 0) SusfsAutoApps.request(this, 1)
                }
            }
        }
        true
    }

    override fun onStopJob(params: JobParameters): Boolean = synchronized(this) {
        running = null; future?.cancel(true); false
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
