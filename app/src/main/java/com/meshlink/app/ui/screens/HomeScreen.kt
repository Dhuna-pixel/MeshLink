package com.meshlink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.repository.TransportMode
import com.meshlink.app.presentation.viewmodels.MeshScreenTab
import com.meshlink.app.presentation.viewmodels.MeshUiState
import com.meshlink.app.ui.components.NodeStatusBadge
import com.meshlink.app.ui.components.PermissionHandler
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshDarkSurfaceVariant
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshPurpleAccent
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary
import com.meshlink.app.ui.theme.TextTertiary

@Composable
fun HomeScreen(
    uiState: MeshUiState,
    onToggleNetworking: () -> Unit,
    onSelectTransportMode: (TransportMode) -> Unit,
    onEditIdentityClicked: () -> Unit,
    onNavigateToTab: (MeshScreenTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MeshDarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Permissions check widget
        PermissionHandler(onPermissionsGranted = {})

        Spacer(modifier = Modifier.height(8.dp))

        // =========================================================================
        // LOCAL NODE IDENTITY CARD
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LOCAL NODE IDENTITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = uiState.currentNode.nodeName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MeshCyanPrimary
                        )
                        Text(
                            text = "ID: ${uiState.currentNode.nodeId}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    IconButton(
                        onClick = onEditIdentityClicked,
                        modifier = Modifier.background(MeshDarkSurfaceVariant, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Node Identity",
                            tint = MeshCyanPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NodeStatusBadge(status = uiState.currentNode.status)

                    val (modeLabel, modeColor) = when (uiState.transportMode) {
                        TransportMode.NEARBY_CONNECTIONS -> "Nearby Connections (P2P)" to MeshGreenAccent
                        TransportMode.BLE_GATT -> "BLE GATT (Cross-Platform)" to MeshCyanPrimary
                        TransportMode.SIMULATION -> "3-Node Simulation" to MeshPurpleAccent
                    }

                    Text(
                        text = "Transport: $modeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = modeColor
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onToggleNetworking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isNetworkingActive) Color(0xFFFF5252) else MeshCyanPrimary,
                        contentColor = MeshDarkBackground
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isNetworkingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isNetworkingActive) "Stop Networking" else "Start Mesh Networking",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // TRANSPORT MODE SELECTOR
        // =========================================================================
        Text(
            text = "Transport Layer Selection",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Option 1: Nearby Connections
                TransportOptionRow(
                    title = "Google Nearby Connections",
                    subtitle = "High-speed Android ↔ Android hardware mesh (P2P Cluster)",
                    icon = Icons.Default.Sensors,
                    isSelected = uiState.transportMode == TransportMode.NEARBY_CONNECTIONS,
                    accentColor = MeshGreenAccent,
                    onClick = { onSelectTransportMode(TransportMode.NEARBY_CONNECTIONS) }
                )

                // Option 2: BLE GATT Mode
                TransportOptionRow(
                    title = "BLE GATT (Android ↔ iOS)",
                    subtitle = "Cross-platform Bluetooth LE GATT Dual-Role transport",
                    icon = Icons.Default.Bluetooth,
                    isSelected = uiState.transportMode == TransportMode.BLE_GATT,
                    accentColor = MeshCyanPrimary,
                    onClick = { onSelectTransportMode(TransportMode.BLE_GATT) }
                )

                // Option 3: Simulation Mode
                TransportOptionRow(
                    title = "3-Node Virtual Simulation",
                    subtitle = "In-memory testbed (Node A → Node B Relay → Node C)",
                    icon = Icons.Default.PlayCircle,
                    isSelected = uiState.transportMode == TransportMode.SIMULATION,
                    accentColor = MeshPurpleAccent,
                    onClick = { onSelectTransportMode(TransportMode.SIMULATION) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // NETWORK TOPOLOGY & METRICS OVERVIEW
        // =========================================================================
        Text(
            text = "Mesh Network Status",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "Connected",
                value = uiState.connectedNodes.size.toString(),
                subtitle = "Active Neighbors",
                icon = Icons.Default.CastConnected,
                color = MeshGreenAccent,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToTab(MeshScreenTab.NEIGHBORS) }
            )

            MetricCard(
                title = "Discovered",
                value = uiState.discoveredNodes.size.toString(),
                subtitle = "Nearby Devices",
                icon = Icons.Default.Hub,
                color = MeshCyanPrimary,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToTab(MeshScreenTab.NEIGHBORS) }
            )

            MetricCard(
                title = "Messages",
                value = uiState.messages.size.toString(),
                subtitle = "Transmitted / RX",
                icon = Icons.Default.Mail,
                color = MeshPurpleAccent,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToTab(MeshScreenTab.MESSAGING) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // QUICK ACTIONS & REVIEW II DEMO SHORTCUTS
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Review II Demonstration Quick Links",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Multi-hop verification concept: Node A ➔ Node B (Relay) ➔ Node C (Destination).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onNavigateToTab(MeshScreenTab.SIMULATION) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("3-Node Visualizer", color = MeshCyanPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onNavigateToTab(MeshScreenTab.LOGS) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Live Event Log", color = MeshGreenAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportOptionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val bg = if (isSelected) MeshDarkSurfaceVariant else Color.Transparent
    val borderModifier = if (isSelected) Modifier.border(1.dp, accentColor, RoundedCornerShape(10.dp)) else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) accentColor else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) accentColor else TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) TextPrimary else TextSecondary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = title, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, style = MaterialTheme.typography.titleLarge, color = color)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextTertiary, fontSize = 10.sp)
        }
    }
}
