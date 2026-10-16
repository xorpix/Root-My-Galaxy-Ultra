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
    private val watchdog = Runnable {
        runCatching { EarlyRootStore.record(this, bootToken, "failed",
            "Early root timed out. Fully reboot before another attempt.", startedLocked, !unlocked()) }
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
                        error.message ?: error.javaClass.simpleName, startedLocked, !unlocked())
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

    private fun trace(stage: String, trigger: String) {
        val (userUnlocked, deviceLocked) = lockEvidence()
        AppLog.info(AppLogTags.BOOT, "Early root $stage: event=$trigger uptimeMs=${BootSettle.elapsedMillis()} " +
            "userUnlocked=$userUnlocked deviceLocked=$deviceLocked")
    }

    private suspend fun runBoot(launch: Intent) {
        val config = EarlyRootStore.config(this) ?: return
        if (!config.requested) return
        bootToken = requireNotNull(kernelBootToken()) { "The kernel boot identity is unavailable" }
        val trigger = launch.getStringExtra(D2Protocol.EARLY_ROOT_EVENT) ?: "unknown"
        val requestUptime = launch.getLongExtra(D2Protocol.EARLY_ROOT_REQUEST_UPTIME, -1)
        val serviceUptime = BootSettle.elapsedMillis()
        trace("worker-start", trigger)
        startedLocked = !unlocked()
        if (packageManager.isSafeMode) {
            EarlyRootStore.record(this, bootToken, "blocked", "Automatic root is skipped in Android safe mode.", startedLocked)
            return
        }
        val prior = EarlyRootStore.result(this)
        val probeStart = BootSettle.elapsedMillis()
        trace("root-probe-start", trigger)
        val alreadyActive = RootStatusProbe.isActive()
        val initialProbeMs = BootSettle.elapsedMillis() - probeStart
        trace("root-probe-finished active=$alreadyActive durationMs=$initialProbeMs", trigger)
        if (alreadyActive) {
            if (prior != null && prior.optString("boot") == bootToken && prior.optString("state") == "verified") {
                finishAfterUnlock(config)
            } else EarlyRootStore.record(this, bootToken, "skipped", "Root was already active", startedLocked)
            return
        }
        if (DfBootAttempt.claimed(this, bootToken)) return

        trace("helper-query-start", trigger)
        val helper = D2Helper.query(this)
        trace("helper-query-finished", trigger)
        if (!helper.controllable || !helper.activated || helper.needsUpdate || helper.needsReboot || !helper.earlyRootEnabled) {
            EarlyRootStore.record(this, bootToken, "blocked", "Update and activate the boot helper, then enable early root again.", startedLocked)
            // The normal after-unlock path remains available when an older helper cannot do Direct Boot.
            if (unlocked()) AutoRootService.start(this)
            return
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:EarlyRoot").apply { acquire(17 * 60_000L) }
        EarlyRootStore.record(this, bootToken, "waiting", "Waiting for the configured boot delay", startedLocked)
        while (true) {
            val policy = EarlyRootStore.config(this) ?: return
            if (!policy.requested) {
                EarlyRootStore.record(this, bootToken, "skipped", "Automatic root was switched off", startedLocked)
                return
            }
            if (BootSettle.remainingMillis(policy.settleSeconds, BootSettle.elapsedMillis()) <= 0) break
            delay(500)
        }
        if (!EarlyRootStore.requested(this) || RootStatusProbe.isActive()) {
            EarlyRootStore.record(this, bootToken, "skipped", "Root is no longer needed or was switched off", startedLocked)
            return
        }
        val current = requireNotNull(EarlyRootStore.config(this))
        val snapshot = EarlyRootDevice.current(helper)
        val profile = DfCatalog.load(this, snapshot).singleOrNull { current.matches(snapshot, it) }
            ?: error("Firmware or bundled backend changed. Complete a manual root run and enable early root again.")
        check(kernelBootToken() == bootToken) { "The kernel boot changed" }
        startedLocked = !unlocked()
        EarlyRootStore.record(this, bootToken, "running", "Starting ${profile.flavor.label}", startedLocked)
        notifyProgress(getString(R.string.early_root_starting))
        handler.postDelayed(watchdog, 6 * 60_000L)
        val payloads = DfCatalog.stage(this, profile, current.stageOptions) {
            AppLog.info(AppLogTags.BOOT, it)
        }
        val exploitUptime = BootSettle.elapsedMillis()
        trace("exploit-start", trigger)
        val runner = DfRunner(this, { AppLog.info(AppLogTags.BOOT, it) }, {}, {
            check(EarlyRootStore.requested(this)) { "Early root was switched off" }
        })
        val outcome = runner.execute(payloads, current.protectImages)
        check(outcome is DfOutcome.Verified) { "Root could not be verified. Check the manager after unlocking and fully reboot before retrying." }
        val (verificationUnlocked, verificationDeviceLocked) = lockEvidence()
        val helperRequestUnlocked = if (launch.hasExtra(D2Protocol.EARLY_ROOT_REQUEST_UNLOCKED))
            launch.getBooleanExtra(D2Protocol.EARLY_ROOT_REQUEST_UNLOCKED, true) else null
        val timing = EarlyRootTiming.classify(trigger, helperRequestUnlocked, verificationUnlocked, verificationDeviceLocked)
        val evidence = JSONObject().put("event", trigger).put("requestUptimeMs", requestUptime)
            .put("serviceUptimeMs", serviceUptime).put("initialProbeMs", initialProbeMs)
            .put("exploitUptimeMs", exploitUptime).put("verificationTiming", timing)
            .put("userUnlocked", verificationUnlocked ?: JSONObject.NULL)
            .put("deviceLocked", verificationDeviceLocked ?: JSONObject.NULL)
        evidence.put("helperRequestUserUnlocked", helperRequestUnlocked ?: JSONObject.NULL)
        EarlyRootStore.record(this, bootToken, "verified", outcome.report, startedLocked,
            timing == EarlyRootTiming.BEFORE, evidence = evidence)
        trace("root-verified timing=$timing", trigger)
        handler.removeCallbacks(watchdog)
        finishAfterUnlock(current)
    }

    /** CE-dependent optional work waits for first unlock; the kernel root is already verified. */
    private suspend fun finishAfterUnlock(config: EarlyRootConfig) {
        if (!unlocked()) return
        val result = EarlyRootStore.result(this) ?: return
        if (result.optString("boot") != bootToken || result.optString("state") != "verified" || result.optBoolean("finalized")) return
        val flavor = requireNotNull(KernelSuFlavor.fromId(config.flavor))
        AppPreferences.setLoadedFlavor(this, flavor, bootToken)
        AutoRootSupport.markVerifiedForBoot(this, bootToken)
        runCatching { KernelSuManagerRefresh.afterLoad(this, flavor) }
            .onFailure { AppLog.warn(AppLogTags.BOOT, "Manager refresh after early root: ${it.message}") }
        if (AppPreferences.susfsAutoEnable(this)) {
            runCatching { SusfsRuntime.activate(this) }.onSuccess { SusfsRuntime.recordActivation(this, it.status) }
                .onFailure { AppLog.warn(AppLogTags.BOOT, "SusFS after early root: ${it.message}") }
        }
        if (AppPreferences.shizukuBootMode(this)) ShizukuBootService.start(this)
        runCatching { StagingSweep.sweepWhenQuiet(this) }
            .onFailure { AppLog.warn(AppLogTags.BOOT, "Cleanup after early root: ${it.message}") }
        EarlyRootStore.finalized(this)
        if (AppPreferences.restartAfterRoot(this)) {
            runCatching { runRecoveryAction(this, RecoveryTool.SoftReboot) }
                .onFailure { AppLog.warn(AppLogTags.BOOT, "Module restart after early root: ${it.message}") }
        }
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
