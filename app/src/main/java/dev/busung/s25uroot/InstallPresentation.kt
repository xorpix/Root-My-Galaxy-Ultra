package dev.busung.s25uroot

/** Whether the UI needs the additional experimental-install dialog after a user's install tap. */
internal fun requiresInstallConfirmation(device: DeviceSnapshot, profile: TargetProfile?): Boolean {
    // Automatic selection uses the existing bundled resolver. An explicit selection must also be
    // a matching bundled profile. Execution still validates the complete profile independently.
    val bundledRoute = when {
        DfPort.matches(device) -> profile == null ||
            (profile.sourceId == DfCatalog.SOURCE && DfCatalog.isDfPayload(profile))
        AzhlPort.identity.matches(device) -> profile == null ||
            (profile.sourceId == AzhlCatalog.SOURCE && AzhlCatalog.isM3qPayload(profile))
        else -> false
    }
    return !bundledRoute || (profile != null && !profile.matches(device))
}
