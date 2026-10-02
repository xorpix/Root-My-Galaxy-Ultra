package dev.busung.s25uroot

/** Shared by online, manual, cached and retry runs after their profile is resolved. */
internal fun backendRunRefusal(
    profile: TargetProfile,
    snapshot: DeviceSnapshot,
    selected: KernelSuFlavor,
    loadedInThisBoot: KernelSuFlavor?,
    rootDetected: Boolean,
): String? = when {
    profile.flavor != selected ->
        "The payload contains ${profile.flavor.label}; ${selected.label} is selected. Choose a matching payload."
    !profile.matches(snapshot) ->
        "The payload does not match this model, firmware, kernel and architecture."
    loadedInThisBoot != null || rootDetected ->
        "Root is already active in this boot. Reboot before loading ${selected.label}."
    else -> null
}
