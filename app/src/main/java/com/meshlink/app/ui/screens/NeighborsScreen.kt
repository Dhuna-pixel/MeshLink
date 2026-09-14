package com.meshlink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.presentation.viewmodels.MeshUiState
import com.meshlink.app.ui.components.NodeStatusBadge
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshRedError
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@Composable
fun NeighborsScreen(
    uiState: MeshUiState,
    onConnectToNode: (Node) -> Unit,
    onDisconnectNode: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MeshDarkBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Nearby & Connected Nodes",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )

            if (uiState.isNetworkingActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                        color = MeshCyanPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scanning...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshCyanPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!uiState.isNetworkingActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.WifiFind,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.height(48.dp).width(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Networking is currently stopped.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                    Text(
                        text = "Start Mesh Networking on the Home screen to discover peers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section 1: Active Connected Neighbors
                item {
                    Text(
                        text = "ACTIVE NEIGHBORS (${uiState.connectedNodes.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshGreenAccent
                    )
                }

                if (uiState.connectedNodes.isEmpty()) {
                    item {
                        Text(
                            text = "No active peer connections established yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else {
                    items(uiState.connectedNodes) { node ->
                        NodeItemCard(
                            node = node,
                            isConnected = true,
                            onConnect = { onConnectToNode(node) },
                            onDisconnect = { onDisconnectNode(node.nodeId) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }

                // Section 2: Discovered Peers
                item {
                    Text(
                        text = "DISCOVERED PEERS (${uiState.discoveredNodes.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshCyanPrimary
                    )
                }

                if (uiState.discoveredNodes.isEmpty()) {
                    item {
                        Text(
                            text = "Scanning for nearby MeshLink devices over Bluetooth / Wi-Fi...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else {
                    items(uiState.discoveredNodes) { node ->
                        NodeItemCard(
                            node = node,
                            isConnected = false,
                            onConnect = { onConnectToNode(node) },
                            onDisconnect = { onDisconnectNode(node.nodeId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeItemCard(
    node: Node,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.nodeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    NodeStatusBadge(status = node.status)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ID: ${node.nodeId}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                node.endpointId?.let { ep ->
                    Text(
                        text = "Endpoint: $ep | Relay Capable: ${if (node.isRelayCapable) "YES" else "NO"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }

            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.LinkOff, contentDescription = null, tint = MeshRedError)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Disconnect", color = MeshRedError)
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MeshCyanPrimary, contentColor = MeshDarkBackground),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Connect")
                }
            }
        }
    }
}
