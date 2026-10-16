package dev.busung.s25uroot

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun EarlyRootSection(rootOnBoot: Boolean, loadKernelSu: Boolean, position: SettingsCardPosition) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var helper by remember { mutableStateOf<D2Status?>(null) }
    var helperReadFailed by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf(EarlyRootDeviceSupport.Checking) }
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lifecycle) {
        // Returning from the helper's NoDisplay activation can resume without another STARTED
        // transition. Also retry transient connection/read failures while settings stay open.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                launch {
                    while (true) {
                        if (!running) {
                            helper = try { D2Helper.query(context).also { helperReadFailed = false } }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { helperReadFailed = true; null }
                        }
                        val currentHelper = helper
                        val support = withContext(Dispatchers.IO) {
                            try {
                                if (DfPort.matches(EarlyRootDevice.current(currentHelper))) EarlyRootDeviceSupport.Supported
                                else EarlyRootDeviceSupport.Unsupported
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { EarlyRootDeviceSupport.Unavailable }
                        }
                        device = support
                        delay(3000)
                    }
                }
                while (true) {
                    val (armed, result) = withContext(Dispatchers.IO) {
                        runCatching { EarlyRootStore.config(context)?.enabled == true }.getOrDefault(false) to EarlyRootStore.result(context)
                    }
                    if (!running) enabled = armed
                    report = result
                    delay(2000)
                }
            }
        }
    }

    val availability = EarlyRootAvailability.current(rootOnBoot, loadKernelSu, device, helper, helperReadFailed)
    val activate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) message = context.getString(R.string.d2_activation_failed)
    }

    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.early_root_title)) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_close)) } })
    }

    Column {
        SettingsSwitchCard(icon = Icons.Rounded.Lock,
            title = stringResource(R.string.early_root_title),
            description = stringResource(when (availability) {
                EarlyRootAvailability.Checking -> R.string.early_root_checking
                EarlyRootAvailability.NeedsBootRoot -> R.string.early_root_needs_boot_root
                EarlyRootAvailability.NeedsKernelLoading -> R.string.settings_boot_root_needs_load
                EarlyRootAvailability.DeviceUnavailable -> R.string.early_root_device_unavailable
                EarlyRootAvailability.Unsupported -> R.string.early_root_unsupported
                EarlyRootAvailability.NeedsHelper -> R.string.early_root_needs_helper
                EarlyRootAvailability.UntrustedHelper -> R.string.early_root_untrusted_helper
                EarlyRootAvailability.NeedsHelperUpdate -> R.string.early_root_needs_helper_update
                EarlyRootAvailability.NeedsReboot -> R.string.early_root_needs_reboot
                EarlyRootAvailability.NeedsActivation -> R.string.early_root_needs_activation
                EarlyRootAvailability.HelperUnavailable -> R.string.early_root_helper_unavailable
                EarlyRootAvailability.Ready -> R.string.early_root_summary
            }),
            checked = enabled, enabled = availability.canChange(enabled, running),
            position = position,
            onCheckedChange = { value ->
                running = true
                scope.launch {
                    try {
                        EarlyRootCoordinator.configure(context, value)
                        enabled = value
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: context.getString(R.string.early_root_failed) }
                    finally { running = false }
                }
            })
        if (availability == EarlyRootAvailability.NeedsActivation) {
            TextButton(enabled = !running, onClick = {
                running = true
                scope.launch {
                    try { activate.launch(D2Helper.activation(context)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: context.getString(R.string.d2_activation_failed) }
                    finally { running = false }
                }
            }) { Text(stringResource(R.string.early_root_activate_helper)) }
        }
        val current = report?.takeIf { it.optString("boot") == kernelBootToken() }
        if (current != null) {
            val state = current.optString("state")
            val text = when (state) {
                "verified" -> context.getString(
                    when (current.optJSONObject("launch")?.optString("verificationTiming")) {
                        EarlyRootTiming.BEFORE -> R.string.early_root_verified_locked
                        EarlyRootTiming.AFTER -> R.string.early_root_verified_unlocked
                        else -> R.string.early_root_verified_unknown
                    }, current.optLong("uptimeMs") / 1000)
                "waiting" -> context.getString(R.string.early_root_waiting)
                "running" -> context.getString(R.string.early_root_starting)
                "skipped" -> context.getString(R.string.early_root_skipped)
                else -> context.getString(R.string.early_root_failed) + "\n" + current.optString("detail")
            }
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = settingsCardRestingShape(position = position)) {
                Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
