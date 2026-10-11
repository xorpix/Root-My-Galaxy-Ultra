package dev.busung.s25uroot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SusfsRow(
    val value: String,
    val activeThisBoot: Boolean,
)

private fun rowState(context: android.content.Context): SusfsRow {
    if (!AppPreferences.susfsAutoEnable(context)) return SusfsRow("Off", false)
    val boot = AutoRootSupport.currentBootToken()
    val active = AppPreferences.susfsState(context) == "active" &&
        AppPreferences.susfsBootToken(context) != null &&
        AppPreferences.susfsBootToken(context) == boot
    return if (active) {
        val version = AppPreferences.susfsVersion(context).ifBlank { "unknown version" }
        SusfsRow("Active · $version", true)
    } else {
        SusfsRow("Unknown — enables on the next run", false)
    }
}

@Composable
internal fun SusfsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = remember { susfsTargetFor(DeviceSnapshot.current()) }
    val compatibility = (target as? SusfsTarget.Unsupported)?.reason
    val untested = (target as? SusfsTarget.FamilyFallback)?.runningRelease
    var busy by remember { mutableStateOf(false) }
    var row by remember { mutableStateOf(rowState(context)) }
    var autoEnabled by remember { mutableStateOf(AppPreferences.susfsAutoEnable(context)) }
    var confirmOff by remember { mutableStateOf(false) }

    fun refreshRow() {
        row = rowState(context)
    }

    // Record an activation outcome for the status row. Runs on IO; never throws.
    fun record(status: SusfsStatus) {
        SusfsRuntime.recordActivation(context, status)
        refreshRow()
    }

    // Best-effort immediate activation (root + grant already present).
    // Without root this just arms the switch: the next run activates.
    fun activateNow() {
        scope.launch {
            busy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching { SusfsRuntime.activate(context) }.getOrElse {
                    SusfsOutcome(
                        SusfsStatus(SusfsState.Unavailable, detail = it.message ?: "SusFS activation failed."),
                        it.message.orEmpty(),
                    )
                }
            }
            busy = false
            record(outcome.status)
        }
    }

    SettingsSwitchCard(
        icon = Icons.Rounded.VisibilityOff,
        title = "SusFS · experimental",
        description = "Enable SusFS automatically after rooting. A full reboot clears it; turning it off " +
            "while active takes effect on reboot.",
        checked = autoEnabled,
        position = SettingsCardPosition.Top,
        enabled = !busy && compatibility == null,
        onCheckedChange = { enabled ->
            if (enabled) {
                AppPreferences.setSusfsAutoEnable(context, true)
                autoEnabled = true
                refreshRow()
                activateNow()
            } else if (row.activeThisBoot) {
                confirmOff = true
            } else {
                AppPreferences.setSusfsAutoEnable(context, false)
                autoEnabled = false
                refreshRow()
            }
        },
    )
    SettingsCard(
        icon = Icons.Rounded.VisibilityOff,
        title = "SusFS status",
        description = "Last activation result on this device. No live checks run from here.",
        value = row.value,
        notice = compatibility
            ?: untested?.let { "Untested kernel — attempt only, verified by feature check." },
        position = SettingsCardPosition.Bottom,
        busy = busy,
        enabled = false,
        onClick = {},
    )
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn SusFS off?") },
            text = {
                Text(
                    "SusFS cannot unload while running: it stays active until you reboot. " +
                        "Before rebooting, turn off any modules that interact with SusFS " +
                        "(hiding, mounts), or they may misbehave on a SusFS-less boot.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppPreferences.setSusfsAutoEnable(context, false)
                    autoEnabled = false
                    confirmOff = false
                    refreshRow()
                }) { Text("Turn off") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) { Text("Cancel") }
            },
        )
    }
}
