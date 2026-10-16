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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The helper owns the switch; verification refreshes automatically when settings are opened. */
@Composable
internal fun D2RecoverySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(D2Status()) }
    var running by remember { mutableStateOf(false) }
    var pendingReboot by remember { mutableStateOf(D2Helper.rebootPending(context)) }
    var prepared by remember { mutableStateOf(D2Helper.prepared(context)) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh(enabled: Boolean? = null) {
        if (running) return
        running = true
        scope.launch {
            try { status = D2Helper.query(context, refresh = enabled == null, enabled = enabled) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                status = status.copy(controllable = false, current = false, verified = false)
                message = context.getString(if (enabled == null) R.string.d2_unavailable else R.string.d2_toggle_failed)
            } finally {
                pendingReboot = D2Helper.rebootPending(context)
                prepared = D2Helper.prepared(context)
                running = false
            }
        }
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val activate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) message = context.getString(R.string.d2_activation_failed)
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
                if (mode == "remove") {
                    EarlyRootCoordinator.configure(context, false)
                    if (status.controllable) D2Helper.query(context, enabled = false)
                }
                val result = D2Helper.setup(context, mode)
                message = context.getString(when {
                    result.rootRequired -> R.string.d2_root_required
                    result.committed && !result.ok -> R.string.d2_commit_uncertain
                    result.ok && mode == "prepare" -> R.string.d2_prepared
                    result.ok -> R.string.d2_removed
                    mode == "prepare" -> R.string.d2_setup_failed
                    else -> R.string.d2_remove_failed
                })
                status = D2Helper.query(context)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                message = context.getString(if (mode == "prepare") R.string.d2_setup_failed else R.string.d2_remove_failed)
            } finally {
                pendingReboot = D2Helper.rebootPending(context)
                prepared = D2Helper.prepared(context)
                running = false
            }
        }
    }

    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.d2_title)) },
            text = { Text(stringResource(if (action == "prepare") R.string.d2_prepare_confirm else R.string.d2_remove_confirm)) },
            confirmButton = { TextButton(onClick = {
                confirming = null
                setup(action)
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
            enabled = !running && !pendingReboot && status.controllable &&
                (status.enabled || (!status.needsUpdate && !status.needsReboot && status.activated)),
            position = SettingsCardPosition.Top,
            onCheckedChange = { refresh(it) },
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = settingsCardRestingShape(position = SettingsCardPosition.Bottom),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val reading = when {
                    running -> R.string.d2_working
                    pendingReboot || status.needsReboot -> R.string.d2_helper_reboot
                    !status.installed && prepared -> R.string.d2_install_needed
                    !status.installed -> R.string.d2_not_installed
                    status.needsUpdate -> R.string.d2_update_required
                    !status.controllable -> R.string.d2_unavailable
                    !status.activated -> R.string.d2_activation_needed
                    !status.enabled -> R.string.d2_ready
                    status.current && status.verified -> R.string.d2_verified
                    else -> R.string.d2_unconfirmed
                }
                Text(stringResource(reading), style = MaterialTheme.typography.titleSmall)
                if (running) LoadingIndicator()
                if (pendingReboot || status.needsReboot) {
                    FilledTonalButton(enabled = !running, onClick = {
                        running = true
                        scope.launch {
                            try { if (!requestReboot()) message = context.getString(R.string.d2_reboot_manual) }
                            finally { running = false }
                        }
                    }) { Text(stringResource(R.string.d2_reboot)) }
                } else if (!status.installed) {
                    Text(stringResource(if (prepared) R.string.d2_install_steps else R.string.d2_setup_steps),
                        style = MaterialTheme.typography.bodyMedium)
                    if (!prepared) FilledTonalButton(enabled = !running, onClick = { confirming = "prepare" }) {
                        Text(stringResource(R.string.d2_prepare))
                    }
                }
                if (!pendingReboot && !status.needsReboot &&
                    ((!status.installed && prepared) || status.needsUpdate || (status.installed && !status.controllable))) {
                    FilledTonalButton(enabled = !running, onClick = {
                        running = true
                        scope.launch {
                            try { installer.launch(D2Helper.installer(context)) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { message = context.getString(R.string.d2_install_failed) }
                            finally { running = false }
                        }
                    }) { Text(stringResource(if (status.installed) R.string.d2_update else R.string.d2_install)) }
                }
                if (status.identityVerified && !status.needsUpdate && !status.needsReboot && !pendingReboot && !status.activated) {
                    FilledTonalButton(enabled = !running, onClick = {
                        running = true
                        scope.launch {
                            try { activate.launch(D2Helper.activation(context)) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { message = context.getString(R.string.d2_activation_failed) }
                            finally { running = false }
                        }
                    }) { Text(stringResource(R.string.d2_activate)) }
                }
                if ((status.installed || prepared) && !pendingReboot && !status.needsReboot) {
                    TextButton(enabled = !running, onClick = { confirming = "remove" }) {
                        Text(stringResource(R.string.d2_remove))
                    }
                }
            }
        }
    }
}
