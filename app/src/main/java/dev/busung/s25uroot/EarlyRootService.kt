package dev.busung.s25uroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

/** Runs in :early_root under the app UID, never in the helper's Android system process. */
class EarlyRootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var again = false
    private var latestStartId = 0
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
        notifyProgress(getString(R.string.early_root_starting), start = true)
        if (active) { again = true; return START_NOT_STICKY }
        active = true
        scope.launch {
            try {
                do {
                    again = false
                    withContext(Dispatchers.IO) { runBoot() }
                } while (again)
            } catch (error: Exception) {
                AppLog.error(AppLogTags.BOOT, "Early root stopped", error)
                runCatching { EarlyRootStore.record(this@EarlyRootService, bootToken, "failed",
                    error.message ?: error.javaClass.simpleName, startedLocked, !unlocked()) }
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

    private suspend fun runBoot() {
        val config = EarlyRootStore.config(this) ?: return
        if (!config.requested) return
        bootToken = requireNotNull(kernelBootToken()) { "The kernel boot identity is unavailable" }
        startedLocked = !unlocked()
        if (packageManager.isSafeMode) {
            EarlyRootStore.record(this, bootToken, "blocked", "Automatic root is skipped in Android safe mode.", startedLocked)
            return
        }
        val prior = EarlyRootStore.result(this)
        if (RootStatusProbe.isActive()) {
            if (prior?.optString("boot") == bootToken && prior.optString("state") == "verified") {
                finishAfterUnlock(config)
            }
            return
        }
        if (DfBootAttempt.claimed(this, bootToken)) return

        val helper = D2Helper.query(this)
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
            if (!policy.requested) return
            if (BootSettle.remainingMillis(policy.settleSeconds, BootSettle.elapsedMillis()) <= 0) break
            delay(500)
        }
        if (!EarlyRootStore.requested(this) || RootStatusProbe.isActive()) return
        val current = requireNotNull(EarlyRootStore.config(this))
        val snapshot = EarlyRootDevice.current()
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
        val runner = DfRunner(this, { AppLog.info(AppLogTags.BOOT, it) }, {}, {
            check(EarlyRootStore.requested(this)) { "Early root was switched off" }
        })
        val outcome = runner.execute(payloads, current.protectImages)
        check(outcome is DfOutcome.Verified) { "Root could not be verified. Check the manager after unlocking and fully reboot before retrying." }
        EarlyRootStore.record(this, bootToken, "verified", outcome.report, startedLocked, !unlocked())
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
            context.startForegroundService(Intent(context, EarlyRootService::class.java).setAction(D2Protocol.EARLY_ROOT_ACTION))
        }
    }
}
