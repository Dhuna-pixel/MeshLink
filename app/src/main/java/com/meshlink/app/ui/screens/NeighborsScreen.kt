package com.meshlink.app.ui.screens

import com.meshlink.app.ui.components.ParticipantName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshlink.app.domain.network.DemoTopology
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.presentation.viewmodels.MeshUiState

@Composable
fun NeighborsScreen(state: MeshUiState, chat: (String) -> Unit, connect: (Node) -> Unit, disconnect: (String) -> Unit,
    pairing: () -> Unit, compose: () -> Unit, everyone: () -> Unit, setBlocked: (String, Boolean) -> Unit, fingerprint: (String) -> String?) {
    val sightings = remember(state.discoveredNodes, state.destinations) {
        // Scanned advertisements carry a short identity token. Only the GATT handshake establishes trust.
        val advertisedIds = state.destinations.associate { peer ->
            val token = DemoTopology.identityToken(peer.id).joinToString("") { "%02x".format(it) }
            "discovered-$token" to peer.id
        }
        state.discoveredNodes.sortedBy { it.lastSeenTimestamp }.associateBy { advertisedIds[it.nodeId] ?: it.nodeId }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.connectNotice.isNotBlank()) item { Text(state.connectNotice) }
        item { OutlinedButton(pairing) { Text("Pair and verify devices") } }
        if (state.isNetworkingActive && state.destinations.any { peer -> peer.canMessage &&
            (state.connectedNodes.any { it.nodeId == peer.id } || state.knownNodes.any { it.nodeId == peer.id }) }) {
            item { Button(everyone) { Text("Message everyone") } }
        }
        if (state.destinations.count { it.canMessage } > 1) item { TextButton(compose) { Text("Message multiple recipients") } }
        if (state.destinations.isEmpty() && state.discoveredNodes.isEmpty()) item {
            Text(if (state.isNetworkingActive) "Looking for nearby devices…" else "Start the mesh on both phones to discover devices.")
        }
        items(state.destinations, key = { it.id }) { peer ->
            var details by remember(peer.id) { mutableStateOf(false) }
            val direct = state.connectedNodes.find { it.nodeId == peer.id }
            val nearby = sightings[peer.id]
            val seen = direct ?: nearby ?: state.knownNodes.find { it.nodeId == peer.id }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                ParticipantName(peer.name, peer.verified, MaterialTheme.typography.titleMedium)
                Text(peer.id, style = MaterialTheme.typography.bodySmall)
                Text(when {
                    direct != null -> "Connected · " + peer.status.substringAfterLast(" · ")
                    nearby?.status == NodeStatus.CONNECTING -> "Connecting…"
                    nearby != null -> "Not connected · " + peer.status.substringAfterLast(" · ")
                    else -> peer.status
                })
                Text(seen?.let { "Last seen ${stamp(it.lastSeenTimestamp)}" } ?: "Last seen unknown", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ details = true }) { Text("Details") }
                    if (peer.canMessage) TextButton({ chat(peer.id) }) { Text("Chat") }
                    else if (peer.blocked) TextButton({ setBlocked(peer.id, false) }) { Text("Unblock") }
                    if (direct != null) TextButton({ disconnect(peer.id) }) { Text("Disconnect") }
                    else if (state.isNetworkingActive && nearby?.endpointId != null)
                        TextButton({ connect(nearby.copy(nodeId = peer.id, nodeName = peer.name, status = NodeStatus.DISCONNECTED)) }, enabled = nearby.status != NodeStatus.CONNECTING) { Text(if (nearby.status == NodeStatus.CONNECTING) "Connecting…" else "Connect") }
                }
            } }
            if (details) AlertDialog(onDismissRequest = { details = false }, title = { ParticipantName(peer.name, peer.verified) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Node ID")
                    Text(peer.id)
                    Text(peer.status)
                    if (peer.about.isNotBlank()) Text(peer.about)
                    TextButton({ setBlocked(peer.id, !peer.blocked); details = false }) { Text(if (peer.blocked) "Unblock" else "Block") }
                    val print = remember(peer.id, peer.canMessage) { fingerprint(peer.id) }
                    print?.let { Text("Card fingerprint"); Text(it,style = MaterialTheme.typography.bodySmall) }
                } }, confirmButton = { TextButton({ details = false }) { Text("Done") } })
        }
        items(sightings.filterKeys { id -> state.destinations.none { it.id == id } }.values.filter { node -> state.connectedNodes.none { it.endpointId == node.endpointId } }) { node ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(node.nodeName, style = MaterialTheme.typography.titleMedium)
                Text(node.nodeId)
                Text(if (node.endpointId in state.rejectedEndpoints) "Secure connection rejected" else "Nearby · not connected")
                Text("Last seen ${stamp(node.lastSeenTimestamp)}")
                TextButton({ connect(node) }, enabled = node.status != NodeStatus.CONNECTING) { Text(if (node.status == NodeStatus.CONNECTING) "Connecting…" else "Connect") }
            } }
        }
    }
}
