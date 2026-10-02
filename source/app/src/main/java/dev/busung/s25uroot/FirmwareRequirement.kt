package dev.busung.s25uroot

import org.json.JSONObject

/** Exact device identity required by a firmware-specific native payload. */
data class FirmwareRequirement(
    val model: String,
    val device: String,
    val incremental: String,
    val kernelRelease: String,
    val sdk: Int,
    val abi: String,
    val pageSize: Long,
) {
    init {
        require(listOf(model, device, incremental, kernelRelease, abi).all(String::isNotBlank)) {
            "Firmware requirements must include the complete device identity"
        }
        require(sdk > 0 && pageSize > 0) { "Invalid firmware SDK or page size" }
    }

    fun matches(snapshot: DeviceSnapshot): Boolean =
        snapshot.manufacturer.equals("samsung", ignoreCase = true) &&
            snapshot.model == model && snapshot.device == device &&
            snapshot.incremental == incremental && snapshot.kernelRelease == kernelRelease &&
            snapshot.sdk == sdk && snapshot.abi == abi && snapshot.pageSize == pageSize &&
            snapshot.machine == "aarch64"

    fun toJsonObject(): JSONObject = JSONObject()
        .put("model", model)
        .put("device", device)
        .put("incremental", incremental)
        .put("kernelRelease", kernelRelease)
        .put("sdk", sdk)
        .put("abi", abi)
        .put("pageSize", pageSize)

    companion object {
        fun parse(json: JSONObject): FirmwareRequirement = FirmwareRequirement(
            model = json.getString("model"),
            device = json.getString("device"),
            incremental = json.getString("incremental"),
            kernelRelease = json.getString("kernelRelease"),
            sdk = json.getInt("sdk"),
            abi = json.getString("abi"),
            pageSize = json.getLong("pageSize"),
        )
    }
}

/** Identity recorded in the supplied AZHL bundle. It is not a compatibility claim. */
internal object AzhlPort {
    val identity = FirmwareRequirement(
        model = "SM-S948B",
        device = "m3q",
        incremental = "S948BXXS4AZHL",
        kernelRelease = "6.12.30-android16-5-pd30ff70-abogkiS948BXXS4AZHL-4k",
        sdk = 36,
        abi = "arm64-v8a",
        pageSize = 4096,
    )

    fun isTargetDevice(snapshot: DeviceSnapshot): Boolean =
        snapshot.model.equals(identity.model, ignoreCase = true) || snapshot.device == identity.device

    fun identityRefusal(snapshot: DeviceSnapshot): String? =
        if (identity.matches(snapshot)) null else
            "This experimental build requires SM-S948B / S948BXXS4AZHL and its exact Android 16, 4 KB kernel."

}

/** Read a declared requirement strictly; a malformed value must never remove the gate. */
internal fun JSONObject.firmwareRequirement(): FirmwareRequirement? =
    if (!has("firmware")) null else FirmwareRequirement.parse(getJSONObject("firmware"))
