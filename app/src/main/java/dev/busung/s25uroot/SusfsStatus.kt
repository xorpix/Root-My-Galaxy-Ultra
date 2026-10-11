package dev.busung.s25uroot

import org.json.JSONObject
import java.util.Locale

internal enum class SusfsState { Active, Partial, Absent, Unavailable }

internal val requiredSusfsFeatures = setOf(
    "CONFIG_KSU_SUSFS_SUS_PATH",
    "CONFIG_KSU_SUSFS_SUS_MOUNT",
    "CONFIG_KSU_SUSFS_SUS_KSTAT",
    "CONFIG_KSU_SUSFS_SPOOF_UNAME",
    "CONFIG_KSU_SUSFS_ENABLE_LOG",
    "CONFIG_KSU_SUSFS_HIDE_KSU_SUSFS_SYMBOLS",
    "CONFIG_KSU_SUSFS_SPOOF_CMDLINE_OR_BOOTCONFIG",
    "CONFIG_KSU_SUSFS_OPEN_REDIRECT",
    "CONFIG_KSU_SUSFS_SUS_MAP",
)

internal data class SusfsStatus(
    val state: SusfsState,
    val version: String = "",
    val variant: String = "",
    val features: Set<String> = emptySet(),
    val detail: String,
    val report: String = "",
)

/** All three native replies must succeed; a partial reply prevents a second load. */
internal fun parseSusfsStatus(output: String, exitCode: Int): SusfsStatus = runCatching {
    val json = JSONObject(output.lineSequence().last { it.trim().startsWith("{") })
    require(json.getInt("schema") == 1)
    val queries = json.getJSONObject("queries")
    val replies = listOf("version", "variant", "features").map { queries.getJSONObject(it) }
    val success = replies.all { it.getLong("syscall") == 0L && it.getInt("error") == 0 && it.getBoolean("terminated") }
    val answered = replies.any { it.getInt("error") != 126 }
    val version = json.getString("version")
    val variant = json.getString("variant")
    val array = json.getJSONArray("features")
    val features = (0 until array.length()).map { array.getString(it) }.toSet()
    val missing = requiredSusfsFeatures - features
    val active = exitCode == 0 && json.getBoolean("available") && success &&
        version.matches(Regex("v?2\\.\\d+\\.\\d+")) && variant.isNotBlank() && missing.isEmpty()
    val state = when {
        active -> SusfsState.Active
        answered -> SusfsState.Partial
        exitCode == 1 && replies.all { it.getBoolean("terminated") && it.getLong("syscall") in setOf(0L, -1L, -22L) } -> SusfsState.Absent
        else -> SusfsState.Unavailable
    }
    SusfsStatus(
        state, version, variant, features,
        when (state) {
            SusfsState.Active -> "$version $variant · ${requiredSusfsFeatures.size} standard features available"
            SusfsState.Partial -> if (missing.isNotEmpty()) "SusFS replied, but these features are missing: ${missing.joinToString()}. Fully reboot before another load."
                else "SusFS returned an incomplete response. Fully reboot before another load."
            SusfsState.Absent -> "SusFS is not active in this boot."
            SusfsState.Unavailable -> "The kernel status check could not complete. See the report."
        },
        output,
    )
}.getOrElse {
    SusfsStatus(SusfsState.Unavailable, detail = "The status helper did not return a valid report (exit $exitCode).", report = output)
}

/** Firmware compatibility is independent of root backend selection. */
internal fun susfsCompatibilityIssue(device: DeviceSnapshot, targetRelease: String): String? = when {
    !device.manufacturer.equals("samsung", ignoreCase = true) ||
        device.model.uppercase(Locale.ROOT).removeSuffix("/DS") != "SM-S948B" ->
        "This module is built for SM-S948B on BZIG."
    device.machine != "aarch64" || device.abi != "arm64-v8a" || device.pageSize != 4096L ->
        "This module requires an ARM64 kernel with 4 KB pages."
    device.kernelRelease != targetRelease -> "Kernel ${device.kernelRelease} does not match the bundled BZIG module."
    else -> null
}
