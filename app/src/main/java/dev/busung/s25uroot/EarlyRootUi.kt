package dev.busung.s25uroot

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
    var helperReady by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val supported = remember { runCatching { DfPort.matches(EarlyRootDevice.current()) }.getOrDefault(false) }

    LaunchedEffect(rootOnBoot, lifecycle) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        val helper = D2Helper.query(context)
        helperReady = helper.controllable && helper.activated && !helper.needsUpdate && !helper.needsReboot
        while (true) {
            val (armed, result) = withContext(Dispatchers.IO) {
                runCatching { EarlyRootStore.config(context)?.enabled == true }.getOrDefault(false) to EarlyRootStore.result(context)
            }
            enabled = armed
            report = result
            delay(2000)
        }
      }
    }

    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.early_root_title)) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_close)) } })
    }

    Column {
        SettingsSwitchCard(icon = Icons.Rounded.Lock,
            title = stringResource(R.string.early_root_title),
            description = stringResource(when {
                !supported -> R.string.early_root_unsupported
                !rootOnBoot -> R.string.early_root_needs_boot_root
                !helperReady -> R.string.early_root_needs_helper
                else -> R.string.early_root_summary
            }),
            checked = enabled, enabled = !running && (enabled || (supported && rootOnBoot && loadKernelSu && helperReady)),
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
        val current = report?.takeIf { it.optString("boot") == kernelBootToken() }
        if (current != null) {
            val state = current.optString("state")
            val text = when (state) {
                "verified" -> context.getString(
                    if (current.optBoolean("finishedLocked")) R.string.early_root_verified_locked
                    else R.string.early_root_verified_unlocked, current.optLong("uptimeMs") / 1000)
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
