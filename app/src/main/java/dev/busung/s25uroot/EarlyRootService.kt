package dev.busung.s25uroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import dev.experimental.rmgu.d2.D2Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Runs in :early_root under the app UID, never in the helper's Android system process. */
class EarlyRootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var again = false
    private var latestStartId = 0
    private var latestLaunch: Intent? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var bootToken = ""
    @Volatile private var startedLocked = false
    private var launchEvidence: JSONObject? = null
    private val watchdog = Runnable {
        runCatching { EarlyRootStore.record(this, bootToken, "failed",
            "Early root timed out. Fully reboot before another attempt.", startedLocked, !unlocked(),
            evidence = launchEvidence) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Coroutine cancellation cannot interrupt a stuck JNI call. Only this dedicated worker dies.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != D2Protocol.EARLY_ROOT_ACTION) { stopSelf(startId); return START_NOT_STICKY }
        if (!EarlyRootStore.requested(this)) { stopSelf(startId); return START_NOT_STICKY }
        latestStartId = startId
        latestLaunch = Intent(intent)
        notifyProgress(getString(R.string.early_root_starting), start = true)
        if (active) { again = true; return START_NOT_STICKY }
        active = true
        scope.launch {
            try {
                do {
                    again = false
                    val launch = requireNotNull(latestLaunch)
                    withContext(Dispatchers.IO) { runBoot(launch) }
                } while (again)
            } catch (error: Exception) {
                AppLog.error(AppLogTags.BOOT, "Early root stopped", error)
                runCatching {
                    val result = EarlyRootStore.result(this@EarlyRootService)
                    if (result?.optString("boot") == bootToken && result.optString("state") == "verified") {
                        EarlyRootStore.finalizationFailed(this@EarlyRootService, error.message ?: error.javaClass.simpleName)
                    } else EarlyRootStore.record(this@EarlyRootService, bootToken, "failed",
                        error.message ?: error.javaClass.simpleName, startedLocked, !unlocked(),
                        evidence = launchEvidence)
                }
            } finally {
                handler.removeCallbacks(watchdog)
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
                active = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestStartId)
            }
        }
        return START_NOT_STICKY
    }

    private fun unlocked(): Boolean = getSystemService(UserManager::class.java).isUserUnlocked

    private fun lockEvidence(): Pair<Boolean?, Boolean?> =
        runCatching { unlocked() }.getOrNull() to
            runCatching { getSystemService(KeyguardManager::class.java).isDeviceLocked }.getOrNull()

    private suspend fun runBoot(launch: Intent) {
        val workerUptime = BootSettle.elapsedMillis()
        val config = EarlyRootStore.config(this) ?: return
        if (!config.requested) return
        bootToken = requireNotNull(kernelBootToken()) { "The kernel boot identity is unavailable" }
        val trigger = launch.getStringExtra(D2Protocol.EARLY_ROOT_EVENT) ?: "unknown"
        val helperRequestUnlocked = if (launch.hasExtra(D2Protocol.EARLY_ROOT_REQUEST_UNLOCKED))
            launch.getBooleanExtra(D2Protocol.EARLY_ROOT_REQUEST_UNLOCKED, true) else null
        launchEvidence = JSONObject().put("event", trigger)
            .put("helperRequestUserUnlocked", helperRequestUnlocked ?: JSONObject.NULL)
            .put("workerUptimeMs", workerUptime)
        for ((extra, field) in listOf(
            D2Protocol.EARLY_ROOT_BROADCAST_UPTIME to "broadcastUptimeMs",
            D2Protocol.EARLY_ROOT_REQUEST_UPTIME to "requestUptimeMs",
        )) {
            val time = launch.getLongExtra(extra, -1L)
            if (time in 0..workerUptime) launchEvidence?.put(field, time)
        }
        startedLocked = !unlocked()
        if (packageManager.isSafeMode) {
            EarlyRootStore.record(this, bootToken, "blocked", "Automatic root is skipped in Android safe mode.",
                startedLocked, evidence = launchEvidence)
            return
        }
        val prior = EarlyRootStore.result(this)
        val alreadyActive = RootStatusProbe.isActive()
        if (alreadyActive) {
            if (prior != null && prior.optString("boot") == bootToken && prior.optString("state") == "verified") {
                holdAwake()
                finishBootSetup(config)
            } else EarlyRootStore.record(this, bootToken, "skipped", "Root was already active", startedLocked,
                evidence = launchEvidence)
            return
        }
        if (DfBootAttempt.claimed(this, bootToken)) return

        val helper = D2Helper.query(this, waitForIdle = false)
        if (!helper.controllable || !helper.activated || helper.needsUpdate || helper.needsReboot || !helper.earlyRootEnabled) {
            EarlyRootStore.record(this, bootToken, "blocked", "Update and activate the boot helper, then enable early root again.",
                startedLocked, evidence = launchEvidence)
            // The normal after-unlock path remains available when an older helper cannot do Direct Boot.
            if (unlocked()) AutoRootService.start(this)
            return
        }
        holdAwake()
        EarlyRootStore.record(this, bootToken, "waiting", "Waiting for the configured boot delay", startedLocked,
            evidence = launchEvidence)
        while (true) {
            val policy = EarlyRootStore.config(this) ?: return
            if (!policy.requested) {
                EarlyRootStore.record(this, bootToken, "skipped", "Automatic root was switched off", startedLocked,
                    evidence = launchEvidence)
                return
            }
            if (BootSettle.remainingMillis(policy.settleSeconds, BootSettle.elapsedMillis()) <= 0) break
            delay(500)
        }
        if (!EarlyRootStore.requested(this) || RootStatusProbe.isActive()) {
            EarlyRootStore.record(this, bootToken, "skipped", "Root is no longer needed or was switched off", startedLocked,
                evidence = launchEvidence)
            return
        }
        val current = requireNotNull(EarlyRootStore.config(this))
        val snapshot = EarlyRootDevice.current(helper)
        val profile = DfCatalog.load(this, snapshot).singleOrNull { current.matches(snapshot, it) }
            ?: error("Firmware or bundled backend changed. Complete a manual root run and enable early root again.")
        check(kernelBootToken() == bootToken) { "The kernel boot changed" }
        startedLocked = !unlocked()
        EarlyRootStore.record(this, bootToken, "running", "Starting ${profile.flavor.label}", startedLocked,
            evidence = launchEvidence)
        notifyProgress(getString(R.string.early_root_starting))
        handler.postDelayed(watchdog, 6 * 60_000L)
        val payloads = DfCatalog.stage(this, profile, current.stageOptions) {
            AppLog.info(AppLogTags.BOOT, it)
        }
        val runner = DfRunner(this, { AppLog.info(AppLogTags.BOOT, it) }, {}, {
            check(EarlyRootStore.requested(this)) { "Early root was switched off" }
        }, adaptiveWait = true)
        launchEvidence?.put("exploitUptimeMs", BootSettle.elapsedMillis())
        val outcome = runner.execute(payloads, current.protectImages)
        check(outcome is DfOutcome.Verified) { "Root could not be verified. Check the manager after unlocking and fully reboot before retrying." }
        val (verificationUnlocked, verificationDeviceLocked) = lockEvidence()
        val timing = EarlyRootTiming.classify(trigger, helperRequestUnlocked, verificationUnlocked, verificationDeviceLocked)
        val evidence = requireNotNull(launchEvidence).put("verificationTiming", timing)
            .put("userUnlocked", verificationUnlocked ?: JSONObject.NULL)
            .put("deviceLocked", verificationDeviceLocked ?: JSONObject.NULL)
        EarlyRootStore.record(this, bootToken, "verified", outcome.report, startedLocked,
            timing == EarlyRootTiming.BEFORE, evidence = evidence)
        AppLog.info(AppLogTags.BOOT, "Early root verified: timing=$timing; ${outcome.report}")
        handler.removeCallbacks(watchdog)
        finishBootSetup(current, snapshot)
    }

    /** Use only DE policy, bundled bytes and the saved su grant until credential storage is open. */
    private suspend fun finishBootSetup(config: EarlyRootConfig, snapshot: DeviceSnapshot? = null) {
        val policy = EarlyRootStore.config(this)?.takeIf { it.requested && it.flavor == config.flavor } ?: return
        notifyProgress(getString(R.string.early_root_setup))
        if (policy.susfsAutoEnable) {
            val claimed = EarlyRootStore.updateVerified(this, bootToken) {
                it.claimSusfs(!unlocked(), BootSettle.elapsedMillis())
            }
            val recovering = EarlyRootStore.result(this)?.let { EarlyRootProgress(it).susfsInProgress } == true
            if (claimed || recovering) {
                val outcome = runCatching {
                    val device = snapshot ?: EarlyRootDevice.current(D2Helper.query(this, waitForIdle = false))
                    val storage = createDeviceProtectedStorageContext()
                    if (claimed) SusfsRuntime.activate(storage, device, SuShell::run)
                    else SusfsRuntime.query(storage, device, SuShell::run)
                }.getOrElse { error ->
                    SusfsOutcome(SusfsStatus(SusfsState.Unavailable,
                        detail = error.message ?: "SusFS activation could not finish."), "")
                }
                EarlyRootStore.updateVerified(this, bootToken) {
                    it.recordSusfs(outcome.status, !unlocked(), BootSettle.elapsedMillis())
                }
                AppLog.info(AppLogTags.BOOT, "Early SusFS: ${outcome.status.detail}")
            }
        }
        // Re-read after activation so disabling automatic root or the restart takes effect now.
        val latest = EarlyRootStore.config(this)
        if (latest?.requested == true && latest.flavor == config.flavor && latest.restartAfterRoot) {
            val claimed = EarlyRootStore.updateVerified(this, bootToken) {
                it.claimRestart(!unlocked(), BootSettle.elapsedMillis())
            }
            if (claimed) {
                // Persist before launching: the restart can kill us before the daemon acknowledges it.
                val outcome = try { restartModules(config) }
                catch (error: Exception) { RecoveryOutcome(false, error.message ?: "Module restart could not finish.") }
                EarlyRootStore.updateVerified(this, bootToken) {
                    it.recordRestart(outcome, !unlocked(), BootSettle.elapsedMillis())
                }
                AppLog.info(AppLogTags.BOOT, "Early module restart accepted=${outcome.accepted}: ${outcome.detail}")
            }
        }
        finishAfterUnlock(config)
    }

    private suspend fun restartModules(config: EarlyRootConfig): RecoveryOutcome {
        check(kernelBootToken() == bootToken) { "The kernel boot changed" }
        // Root/SusFS overlap the repeated Odin checks, but our planned restart must not kill them.
        val helper = D2Helper.query(this)
        val helperIdle = helper.controllable && !helper.needsUpdate && !helper.needsReboot && !helper.busy
        check(helperIdle) {
            "The boot helper has not finished its Odin checks; the automatic restart was not started"
        }
        check(isRootAnswer(SuShell.run("id", 15))) { "The app's saved root permission is unavailable" }
        val shell: (String) -> ShizukuController.ShellResult = { command ->
            SuShell.run(command, 90) ?: ShizukuController.ShellResult(127, "The root shell did not answer")
        }
        val daemon = RootRecovery.softRebootDaemon(applicationInfo.nativeLibraryDir,
            M3qLaunch.backend(config.flavor), shell)
        val capabilities = RootRecovery.capabilities(shell, daemon) ?: KsudCapabilities()
        val latest = EarlyRootStore.config(this)
        check(latest?.requested == true && latest.flavor == config.flavor && latest.restartAfterRoot) {
            "Automatic root or the module restart was switched off"
        }
        EarlyRootStore.updateVerified(this, bootToken) { it.recordRestartRequest(!unlocked(), BootSettle.elapsedMillis()) }
        return RootRecovery.softReboot(shell, bootToken, capabilities, daemon)
    }

    private fun holdAwake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:EarlyRoot").apply { acquire(17 * 60_000L) }
    }

    /** Only UI bookkeeping, manager refresh and Shizuku still require CE settings. */
    private suspend fun finishAfterUnlock(config: EarlyRootConfig) {
        if (!unlocked()) return
        val result = EarlyRootStore.result(this) ?: return
        if (result.optString("boot") != bootToken || result.optString("state") != "verified" || result.optBoolean("finalized")) return
        val flavor = requireNotNull(KernelSuFlavor.fromId(config.flavor))
        AppPreferences.setLoadedFlavor(this, flavor, bootToken)
        AutoRootSupport.markVerifiedForBoot(this, bootToken)
        runCatching { KernelSuManagerRefresh.afterLoad(this, flavor) }
            .onFailure { AppLog.warn(AppLogTags.BOOT, "Manager refresh after early root: ${it.message}") }
        if (result.has("susfsState")) {
            AppPreferences.recordSusfsActivation(this,
                if (result.optString("susfsState") == "active") "active" else "other",
                result.optString("susfsVersion"), bootToken)
        }
        if (AppPreferences.shizukuBootMode(this)) {
            runCatching { ShizukuBootService.start(this) }
                .onFailure { AppLog.warn(AppLogTags.BOOT, "Shizuku after early root: ${it.message}") }
        }
        runCatching { StagingSweep.sweepWhenQuiet(this) }
            .onFailure { AppLog.warn(AppLogTags.BOOT, "Cleanup after early root: ${it.message}") }
        EarlyRootStore.finalized(this)
    }

    private fun notifyProgress(text: String, start: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.early_root_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.early_root_title)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        if (start) {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
        } else manager.notify(NOTIFICATION, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PROCESS_SUFFIX = ":early_root"
        private const val CHANNEL = "early_root"
        private const val NOTIFICATION = 2104
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EarlyRootService::class.java)
                .setAction(D2Protocol.EARLY_ROOT_ACTION)
                .putExtra(D2Protocol.EARLY_ROOT_EVENT, Intent.ACTION_BOOT_COMPLETED)
                .putExtra(D2Protocol.EARLY_ROOT_REQUEST_UPTIME, BootSettle.elapsedMillis()))
        }
    }
}
