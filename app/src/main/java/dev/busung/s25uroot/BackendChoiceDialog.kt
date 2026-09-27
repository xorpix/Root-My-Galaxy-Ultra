package dev.busung.s25uroot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Saving a choice changes a preference only; it never starts a root attempt. */
@Composable
internal fun BackendChoiceDialog(onChoose: (KernelSuFlavor) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = KernelSuFlavor.fromId(selectedId)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.backend_choice_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backend_choice_explanation))
                Column(Modifier.selectableGroup()) {
                    KernelSuFlavor.entries.forEach { flavor ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == flavor,
                                    onClick = { selectedId = flavor.id },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == flavor, onClick = null)
                            Text(flavor.label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onChoose) }) {
                Text(stringResource(R.string.backend_choice_save))
            }
        },
    )
}
