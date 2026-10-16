package dev.busung.s25uroot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object EarlyRootCoordinator {
    suspend fun configure(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (!enabled) {
            // The local policy is authoritative even when the helper is missing or needs an update.
            EarlyRootStore.disable(context)
            runCatching { D2Helper.setEarlyRoot(context, false) }
            return@withContext
        }
        check(AppPreferences.bootRootMode(context)) { "Enable Root on boot first" }
        val helper = D2Helper.query(context)
        check(helper.controllable && helper.activated && !helper.needsUpdate && !helper.needsReboot) {
            "Update the boot helper in Recovery Management, fully reboot, then activate it"
        }
        val snapshot = EarlyRootDevice.current(helper)
        check(AutoRootSupport.hasVerifiedInstall(context, snapshot)) { "Complete a verified root run before enabling early root" }
        val cached = KnownGoodPayloadStore.profileFor(context, snapshot = snapshot)
        check(DfCatalog.isDfPayload(cached)) { "Early root currently requires the bundled DirtyFrag backend" }
        val profile = DfCatalog.load(context, snapshot).singleOrNull {
            it.flavor == AppPreferences.kernelsuFlavor(context) && it.matches(snapshot) &&
                it.kernelSu.sha256 == cached.kernelSu.sha256 && it.flavor == cached.flavor
        } ?: error("Run the selected bundled backend on this firmware before enabling early root")
        val config = EarlyRootConfig(false, true, profile.flavor.id, snapshot.model, snapshot.device,
            snapshot.incremental, snapshot.kernelRelease, requireNotNull(profile.kernelSu.sha256),
            AppPreferences.autoRootSettleSeconds(context), AppPreferences.disableKsuModules(context),
            AppPreferences.wipeModuleState(context), AppPreferences.partitionReadOnlyMode(context),
            AppPreferences.susfsAutoEnable(context), AppPreferences.restartAfterRoot(context))
        EarlyRootStore.save(context, config)
        D2Helper.setEarlyRoot(context, true)
        EarlyRootStore.save(context, config.copy(enabled = true))
    }
}
