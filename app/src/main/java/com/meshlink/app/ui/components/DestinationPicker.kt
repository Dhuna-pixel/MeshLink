package com.meshlink.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meshlink.app.domain.network.DestinationOption

@Composable
fun DestinationPicker(selectedIds: Set<String>, options: List<DestinationOption>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.filter { it.id in selectedIds }
    OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (selected.isEmpty()) "Choose recipients" else "Recipients (${selected.size})")
            Text(
                if (options.isEmpty()) "No other nodes known"
                else if (selected.isEmpty()) "Select one or more neighbors or mesh nodes"
                else selected.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Choose recipients (${selected.size})") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item { Text("Select one or more devices. Direct neighbors appear first.", style = MaterialTheme.typography.bodySmall) }
                    items(options, key = { it.id }) { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(value = option.id in selectedIds, role = Role.Checkbox, enabled = option.canMessage, onValueChange = { onSelect(option.id) })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = option.id in selectedIds, onCheckedChange = null, enabled = option.canMessage)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                ParticipantName(option.name, option.verified, MaterialTheme.typography.bodyLarge)
                                Text(option.status, style = MaterialTheme.typography.bodySmall)
                                Text("ID: ${option.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Done") } }
        )
    }
}
