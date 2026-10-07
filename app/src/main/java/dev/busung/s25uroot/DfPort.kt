package dev.busung.s25uroot

/** S26 Ultra family eligibility for the bundled Android 16 / Linux 6.12 DirtyFrag helper. */
internal object DfPort {
    // Includes regional suffixes such as B, U1, W, N, 0, 0V and the Japanese SM models.
    private val modelPattern = Regex("SM-S948[A-Z0-9]{1,2}(?:/DS)?", RegexOption.IGNORE_CASE)
    private val carrierModels = setOf("SC-53G", "SCG37")
    private val kernelPattern = Regex("6\\.12\\.\\d+-android16-\\d+(?:-\\S+)?")

    /** Recorded identities keep their existing catalog IDs; other builds get runtime profiles. */
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

    private fun isFamilyModel(model: String): Boolean =
        modelPattern.matches(model) || carrierModels.any { it.equals(model, ignoreCase = true) }

    /** Recognizes the family even when a generic profile omits its firmware requirement. */
    fun isTargetDevice(snapshot: DeviceSnapshot): Boolean =
        isFamilyModel(snapshot.model) || snapshot.device == "m3q"

    private fun supportsIdentity(
        model: String, device: String, incremental: String, kernelRelease: String,
        sdk: Int, abi: String, pageSize: Long,
    ): Boolean = isFamilyModel(model) && device == "m3q" && incremental.isNotBlank() &&
        sdk >= 36 && abi == "arm64-v8a" && pageSize == 4096L && kernelPattern.matches(kernelRelease)

    fun supports(firmware: FirmwareRequirement): Boolean = firmware != AzhlPort.identity &&
        supportsIdentity(firmware.model, firmware.device, firmware.incremental, firmware.kernelRelease,
            firmware.sdk, firmware.abi, firmware.pageSize)

    fun matches(snapshot: DeviceSnapshot): Boolean =
        snapshot.manufacturer.equals("samsung", ignoreCase = true) && snapshot.machine == "aarch64" &&
            !AzhlPort.identity.matches(snapshot) &&
            supportsIdentity(snapshot.model, snapshot.device, snapshot.incremental, snapshot.kernelRelease,
                snapshot.sdk, snapshot.abi, snapshot.pageSize)

    /** Bind every generated profile, cache and retry to the complete observed identity. */
    fun firmwareFor(snapshot: DeviceSnapshot): FirmwareRequirement? = if (!matches(snapshot)) null else
        FirmwareRequirement(snapshot.model, snapshot.device, snapshot.incremental, snapshot.kernelRelease,
            snapshot.sdk, snapshot.abi, snapshot.pageSize)

    fun identityRefusal(snapshot: DeviceSnapshot): String? =
        if (matches(snapshot)) null else
            "DirtyFrag requires a Samsung Galaxy S26 Ultra (m3q), ARM64 with 4 KB pages, " +
                "Android API 36 or newer, a non-empty firmware ID and an android16 / Linux 6.12 GKI kernel."
}
