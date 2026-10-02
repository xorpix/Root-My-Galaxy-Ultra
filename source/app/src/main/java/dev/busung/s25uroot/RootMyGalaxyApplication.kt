package dev.busung.s25uroot

import android.app.Application
import android.content.Context
import rikka.shizuku.ShizukuProvider

/**
 * Turns on Shizuku's cross-process binder sharing, for every process this app runs in.
 *
 * The provider lives in the default process and nothing else shares its binder by default, so a
 * secondary process - and this app has one, the Auto Root gate - would look at Shizuku and see
 * nothing running. That is invisible from the launcher: a manual run in the default process works,
 * and the automation that runs in the gate process quietly falls back to its own shell.
 *
 * The flag is per role rather than global on purpose. Only the provider's own process hosts the
 * binder, and asking a process that is not the host to share one would be asking it to do the
 * provider's job; the others ask for the binder the provider already has, at startup, because the
 * process can be created after Shizuku delivered it and the delivery broadcast has already been sent.
 */
class RootMyGalaxyApplication : Application() {
    private var isShizukuProviderProcess = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        isShizukuProviderProcess = Application.getProcessName() == base.packageName
        ShizukuProvider.enableMultiProcessSupport(isShizukuProviderProcess)
    }

    override fun onCreate() {
        super.onCreate()

        // Every process, including the two services: the Logs tab is where a boot install explains
        // itself, and a boot install has no screen to be explained on at the time.
        AppLog.install(this)

        // A run killed with its process cannot take its own notification down, and one left claiming an
        // install that is not happening is worse than none: it would keep someone waiting and its Stop
        // would do nothing. Asked of the record every process shares, so a boot run in flight keeps its own.
        RunNotification.clearStale(this)

        if (!isShizukuProviderProcess) {
            ShizukuProvider.requestBinderForNonProviderProcess(this)
        }
    }
}
