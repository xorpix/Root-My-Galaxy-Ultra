package dev.busung.s25uroot

/** Exact identities routed to the bundled Android 16 / Linux 6.12 DirtyFrag helper. */
internal object DfPort {
    val firmwares = mapOf(
        "bzig" to FirmwareRequirement(
            model = "SM-S948B",
            device = "m3q",
            incremental = "S948BXXS4BZIG",
            kernelRelease = "6.12.69-android16-6-pb4d3caf-abogkiS948BXXS4BZIG-4k",
            sdk = 37,
            abi = "arm64-v8a",
            pageSize = 4096,
        ),
        "bzid" to FirmwareRequirement(
            model = "SM-S948W",
            device = "m3q",
            incremental = "S948WVLU4BZID",
            // The Canadian inventory reports this U-series kernel; retain it verbatim.
            kernelRelease = "6.12.69-android16-6-pee899be-abogkiS948USQU4BZID-4k",
            sdk = 37,
            abi = "arm64-v8a",
            pageSize = 4096,
        ),
    )

    fun matches(snapshot: DeviceSnapshot): Boolean = firmwares.values.any { it.matches(snapshot) }

    fun identityRefusal(snapshot: DeviceSnapshot): String? =
        if (matches(snapshot)) null else
            "This experimental DirtyFrag build requires " +
                firmwares.values.joinToString(" or ") { "${it.model} / ${it.incremental}" } +
                " (OneUI 9) and its exact Android 16, 4 KB kernel."
}
