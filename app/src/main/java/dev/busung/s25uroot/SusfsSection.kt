package dev.busung.s25uroot

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    var busy by remember { mutableStateOf(false) }
    var row by remember { mutableStateOf(rowState(context)) }
    var autoEnabled by remember { mutableStateOf(AppPreferences.susfsAutoEnable(context)) }

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
            AppPreferences.setSusfsAutoEnable(context, enabled)
            autoEnabled = enabled
            refreshRow()
            if (enabled) activateNow()
        },
    )
    SusfsStatusCard(
        row = row,
        compatibility = compatibility,
        position = SettingsCardPosition.Bottom,
        busy = busy,
    )
}

/** A readable status, without a disabled action or a narrow trailing-value column. */
@Composable
private fun SusfsStatusCard(
    row: SusfsRow,
    compatibility: String?,
    position: SettingsCardPosition,
    busy: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = settingsCardRestingShape(position),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_susfs_status_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.activeThisBoot) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (compatibility != null) {
                        Text(
                            compatibility,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            }
        }
    }
}
