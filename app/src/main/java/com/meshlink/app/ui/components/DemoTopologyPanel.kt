package com.meshlink.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.meshlink.app.domain.network.DemoTopology

@Composable
fun DemoTopologyPanel(localId: String, topology: DemoTopology, message: String, busy: Boolean, onApply: (Boolean,String,Boolean) -> Unit) {
    var chain by remember { mutableStateOf(true) }
    var ids by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Testing only · direct BLE links",style = MaterialTheme.typography.titleMedium)
            Text(if (topology.enabled) "Controlled BLE Demo Mode — real BLE GATT, restricted direct links" else "Normal physical BLE mesh · automatic links to participating devices",style = MaterialTheme.typography.bodySmall)
            Row {
                Switch(checked = topology.enabled,onCheckedChange = { onApply(it,"",false) },enabled = !busy)
                Text(if (topology.enabled) "Demo restrictions ON" else "Demo restrictions OFF")
            }
            Text("Local node ID",style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(localId,style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(localId)) }) { Text("Copy local ID") }
            if (topology.enabled) {
                Text("Permitted direct neighbors: ${topology.permittedIds.joinToString().ifEmpty { "NONE — all direct links blocked" }}",style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(checked = chain,onCheckedChange = { chain = it })
                    Text("Use an ordered chain of all phone IDs")
                }
                Text(if (chain) "Paste the SAME ordered list on every phone, one exact node ID per line. Your position determines your neighbors." else "Enter only permitted direct-neighbor IDs, one per line. Both peers must allow each other.",style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = ids,onValueChange = { ids = it },label = { Text(if (chain) "Ordered phone IDs" else "Allowed neighbor IDs") },modifier = Modifier.fillMaxWidth(),minLines = 3,maxLines = 6)
                Button(onClick = { onApply(true,ids,chain) },enabled = !busy) { Text("Apply BLE topology") }
                Text("Applying changes reconnects BLE and clears routes. Unknown IDs never grant access. Turn OFF to remove every restriction.",style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message,style = MaterialTheme.typography.bodySmall)
        }
    }
}
