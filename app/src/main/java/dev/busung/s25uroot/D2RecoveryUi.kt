package dev.busung.s25uroot

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/** The switch belongs to the helper; the launcher never persists a second, potentially divergent copy. */
@Composable
internal fun D2RecoverySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(D2Status()) }
    var running by remember { mutableStateOf(false) }
    var pendingReboot by remember { mutableStateOf(D2Helper.rebootPending(context)) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh(enabled: Boolean? = null) {
        if (running) return
        running = true
        scope.launch {
            try { status = D2Helper.query(context, refresh = enabled == null, enabled = enabled) }
            catch (error: Exception) { message = error.message ?: context.getString(R.string.d2_unavailable) }
            finally { pendingReboot = D2Helper.rebootPending(context); running = false }
        }
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val activate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) message = it.data?.getStringExtra("error")
            ?: context.getString(R.string.d2_activation_failed)
        refresh()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun setup(mode: String) {
        if (running) return
        running = true
        scope.launch {
            try {
                if (mode == "remove" && status.controllable) D2Helper.query(context, enabled = false)
                val result = D2Helper.setup(context, mode)
                message = result.detail + if (result.committed && !result.ok) "\n\n" + context.getString(R.string.d2_commit_uncertain) else ""
                status = D2Helper.query(context)
            } catch (error: Exception) { message = error.message ?: context.getString(R.string.d2_unavailable) }
            finally { pendingReboot = D2Helper.rebootPending(context); running = false }
        }
    }

    confirming?.let { action ->
        val description = when (action) {
            "prepare" -> R.string.d2_prepare_confirm
            "remove" -> R.string.d2_remove_confirm
            else -> R.string.d2_enable_confirm
        }
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.d2_title)) },
            text = { Text(stringResource(description)) },
            confirmButton = { TextButton(onClick = {
                confirming = null
                if (action == "enable") refresh(true) else setup(action)
            }) { Text(stringResource(R.string.d2_continue)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    message?.let { detail ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.d2_title)) },
            text = { Text(detail) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSwitchCard(
            icon = Icons.Rounded.Shield,
            title = stringResource(R.string.d2_title),
            description = stringResource(R.string.d2_summary),
            checked = status.enabled,
            enabled = !running && status.controllable &&
                (status.enabled || (!status.needsUpdate && !status.needsReboot && status.activated)),
            position = SettingsCardPosition.Top,
            onCheckedChange = { enabled -> if (enabled) confirming = "enable" else refresh(false) },
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = settingsCardRestingShape(position = SettingsCardPosition.Bottom),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val reading = when {
                    !status.installed -> R.string.d2_not_installed
                    !status.controllable -> R.string.d2_unavailable
                    status.needsUpdate -> R.string.d2_update_required
                    status.needsReboot -> R.string.d2_helper_reboot
                    !status.activated -> R.string.d2_activation_needed
                    !status.current -> R.string.d2_not_checked
                    status.verified -> R.string.d2_verified
                    else -> R.string.d2_unconfirmed
                }
                Text(stringResource(reading), style = MaterialTheme.typography.titleSmall)
                if (status.current && status.lock >= 0) Text(
                    stringResource(R.string.d2_flags, status.lock, status.maintenance, status.at, status.ageMs / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.d2_limit), style = MaterialTheme.typography.bodySmall)
                if (running) LoadingIndicator()
                if (pendingReboot || status.needsReboot) {
                    Text(stringResource(if (pendingReboot) R.string.d2_reboot_required else R.string.d2_helper_reboot),
                        color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(enabled = !running, onClick = {
                        running = true
                        scope.launch {
                            try { if (!requestReboot()) message = context.getString(R.string.d2_reboot_manual) }
                            finally { running = false }
                        }
                    }) { Text(stringResource(R.string.d2_reboot)) }
                }
                if (!status.controllable || status.needsUpdate) {
                    Text(stringResource(R.string.d2_setup_steps), style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !running && !pendingReboot, onClick = { confirming = "prepare" }) {
                        Text(stringResource(R.string.d2_prepare))
                    }
                    FilledTonalButton(enabled = !running && !pendingReboot, onClick = {
                        running = true
                        scope.launch {
                            try { installer.launch(D2Helper.installer(context)) }
                            catch (error: Exception) { message = error.message ?: context.getString(R.string.d2_install_failed) }
                            finally { running = false }
                        }
                    }) { Text(stringResource(if (status.installed) R.string.d2_update else R.string.d2_install)) }
                }
                if (status.installed && status.controllable && !status.needsUpdate && !status.needsReboot) {
                    TextButton(enabled = !running, onClick = {
                        try { activate.launch(D2Helper.activation()) }
                        catch (error: Exception) { message = error.message ?: context.getString(R.string.d2_activation_failed) }
                    }) { Text(stringResource(R.string.d2_activate)) }
                }
                TextButton(enabled = !running, onClick = { refresh() }) { Text(stringResource(R.string.d2_check)) }
                TextButton(enabled = !running, onClick = { setup("inspect") }) { Text(stringResource(R.string.d2_inspect)) }
                TextButton(enabled = !running, onClick = { confirming = "remove" }) { Text(stringResource(R.string.d2_remove)) }
            }
        }
    }
}
