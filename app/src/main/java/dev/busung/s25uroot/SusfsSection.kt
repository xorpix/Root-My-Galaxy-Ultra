package dev.busung.s25uroot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SusfsRow(
    val value: String,
    val activeThisBoot: Boolean,
)

private fun rowState(context: android.content.Context): SusfsRow {
    val automatic = AppPreferences.susfsAutoEnable(context)
    val boot = AutoRootSupport.currentBootToken()
    val active = AppPreferences.susfsState(context) == "active" &&
        AppPreferences.susfsBootToken(context) != null &&
        AppPreferences.susfsBootToken(context) == boot
    val version = AppPreferences.susfsVersion(context)
        .ifBlank { context.getString(R.string.settings_susfs_version_unknown) }
    val state = SettingsPresentation.susfsState(automatic, active)
    val value = when (state) {
        SettingsPresentation.SusfsState.ACTIVE ->
            context.getString(R.string.settings_susfs_active, version)
        SettingsPresentation.SusfsState.ACTIVE_UNTIL_REBOOT ->
            context.getString(R.string.settings_susfs_active_until_reboot, version)
        SettingsPresentation.SusfsState.PENDING ->
            context.getString(R.string.settings_susfs_pending)
        SettingsPresentation.SusfsState.OFF ->
            context.getString(R.string.settings_susfs_off)
    }
    return SusfsRow(value, active)
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
        title = stringResource(R.string.settings_susfs_title),
        description = stringResource(R.string.settings_susfs_summary),
        checked = autoEnabled,
        position = SettingsCardPosition.Top,
        enabled = SettingsPresentation.canChangeSusfs(autoEnabled, busy, compatibility == null),
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
        title = stringResource(R.string.settings_susfs_status_title),
        description = stringResource(R.string.settings_susfs_status_summary),
        value = row.value,
        notice = compatibility
            ?: untested?.let { stringResource(R.string.settings_susfs_untested) },
        position = SettingsCardPosition.Bottom,
        busy = busy,
        enabled = false,
        onClick = {},
    )
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.settings_susfs_off_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_susfs_off_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppPreferences.setSusfsAutoEnable(context, false)
                    autoEnabled = false
                    confirmOff = false
                    refreshRow()
                }) { Text(stringResource(R.string.settings_susfs_turn_off)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

