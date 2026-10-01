package dev.busung.s25uroot

/** Identity recorded in the OneUI 9 BZIG bundle. It is not a compatibility claim. */
internal object BzigPort {
    val identity = FirmwareRequirement(
        model = "SM-S948B",
        device = "m3q",
        incremental = "S948BXXS4BZIG",
        kernelRelease = "6.12.69-android16-6-pb4d3caf-abogkiS948BXXS4BZIG-4k",
        sdk = 37,
        abi = "arm64-v8a",
        // Same physical phone as AZHL; confirm once with `getconf PAGESIZE`.
        pageSize = 4096,
    )

    fun identityRefusal(snapshot: DeviceSnapshot): String? =
        if (identity.matches(snapshot)) null else
            "This experimental build requires SM-S948B / S948BXXS4BZIG (OneUI 9) and its exact Android 16, 4 KB kernel."
}
