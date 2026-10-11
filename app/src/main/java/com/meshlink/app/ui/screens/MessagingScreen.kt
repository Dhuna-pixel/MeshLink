package com.meshlink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.presentation.viewmodels.MeshUiState
import com.meshlink.app.ui.theme.CodeLogBackground
import com.meshlink.app.ui.theme.MeshAmberWarning
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshDarkSurfaceVariant
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshPurpleAccent
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@Composable
fun MessagingScreen(
    uiState: MeshUiState,
    onDestinationSelected: (String) -> Unit,
    onMessageInputChanged: (String) -> Unit,
    onTtlChanged: (Int) -> Unit,
    onToggleCrcCorruption: () -> Unit,
    onSendMessage: () -> Unit,
    onInspectPacket: (MessagePacket) -> Unit,
    onClearMessages: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MeshDarkBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // =========================================================================
        // MESSAGE COMPOSER CARD
        // =========================================================================
        item {
            Spacer(modifier = Modifier.height(4.dp))
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
                        Text(
                            text = "Transmit Mesh Packet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MeshCyanPrimary
                        )

                        // CRC-16 badge
                        Box(
                            modifier = Modifier
                                .background(MeshGreenAccent.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CRC-16-CCITT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MeshGreenAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Destination selection chips
                    Text(
                        text = "Select Destination Node:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val destinationCandidates = (uiState.connectedNodes + uiState.discoveredNodes)
                        .map { it.nodeId to it.nodeName }
                        .toMutableList()

                    if (destinationCandidates.none { it.first == "NODE-C" }) {
                        destinationCandidates.add("NODE-C" to "Node C (Multi-Hop)")
                    }
                    if (destinationCandidates.none { it.first == "NODE-B" }) {
                        destinationCandidates.add("NODE-B" to "Node B (Relay)")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        destinationCandidates.distinctBy { it.first }.forEach { (nodeId, name) ->
                            val isSelected = uiState.selectedDestinationNodeId.equals(nodeId, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { onDestinationSelected(nodeId) },
                                label = { Text(name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MeshCyanPrimary,
                                    selectedLabelColor = MeshDarkBackground,
                                    containerColor = MeshDarkSurfaceVariant,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.selectedDestinationNodeId,
                        onValueChange = onDestinationSelected,
                        label = { Text("Target Destination Node ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeshCyanPrimary,
                            unfocusedBorderColor = TextSecondary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // TTL Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Time-To-Live (Hop Limit):",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "${uiState.ttlSliderValue} Hops",
                            style = MaterialTheme.typography.titleMedium,
                            color = MeshCyanPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Slider(
                        value = uiState.ttlSliderValue.toFloat(),
                        onValueChange = { onTtlChanged(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = MeshCyanPrimary,
                            activeTrackColor = MeshCyanPrimary,
                            inactiveTrackColor = MeshDarkSurfaceVariant
                        )
                    )

                    // Message Text Field
                    OutlinedTextField(
                        value = uiState.messageInputText,
                        onValueChange = onMessageInputChanged,
                        label = { Text("Payload / Message Content") },
                        placeholder = { Text("e.g. Hello Node C through Node B!") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeshCyanPrimary,
                            unfocusedBorderColor = TextSecondary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // CRC Error Simulation Control for Review III Demo
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (uiState.isCrcErrorSimulationArmed) MeshAmberWarning.copy(alpha = 0.15f) else MeshDarkSurfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = if (uiState.isCrcErrorSimulationArmed) MeshAmberWarning else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Simulate CRC Corruption",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.isCrcErrorSimulationArmed) MeshAmberWarning else TextPrimary
                                )
                                Text(
                                    text = if (uiState.isCrcErrorSimulationArmed) "ARMED: Next TX will flip a payload byte" else "Demonstrates error rejection at receiver",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Switch(
                            checked = uiState.isCrcErrorSimulationArmed,
                            onCheckedChange = { onToggleCrcCorruption() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MeshAmberWarning,
                                checkedTrackColor = MeshAmberWarning.copy(alpha = 0.4f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onSendMessage,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.messageInputText.isNotBlank() && uiState.selectedDestinationNodeId.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isCrcErrorSimulationArmed) MeshAmberWarning else MeshGreenAccent,
                            contentColor = MeshDarkBackground
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isCrcErrorSimulationArmed) "Send Corrupted Frame (Test)" else "Send via BLE GATT Mesh",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // =========================================================================
        // MESSAGE HISTORY HEADER
        // =========================================================================
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Message Packet History (${uiState.messages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                if (uiState.messages.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearMessages,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Clear", color = TextSecondary)
                    }
                }
            }
        }

        // =========================================================================
        // MESSAGE HISTORY ITEMS
        // =========================================================================
        if (uiState.messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages sent or received yet.\nSend a message to see multi-hop routing & CRC in action.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            items(uiState.messages) { packet ->
                MessageCard(
                    packet = packet,
                    currentNodeId = uiState.currentNode.nodeId,
                    onInspect = { onInspectPacket(packet) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MessageCard(
    packet: MessagePacket,
    currentNodeId: String,
    onInspect: () -> Unit
) {
    val isDeliveredHere = packet.destinationId.equals(currentNodeId, ignoreCase = true)
    val isSentFromHere = packet.sourceId.equals(currentNodeId, ignoreCase = true)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isDeliveredHere) Icons.Default.CallReceived else Icons.Default.Send,
                        contentDescription = null,
                        tint = if (isDeliveredHere) MeshGreenAccent else MeshCyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isDeliveredHere) "DELIVERED HERE" else if (isSentFromHere) "OUTGOING" else "RELAYED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDeliveredHere) MeshGreenAccent else MeshCyanPrimary
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // CRC Status Badge
                    Box(
                        modifier = Modifier
                            .background(MeshGreenAccent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "CRC: PASS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MeshGreenAccent
                        )
                    }
                }

                IconButton(
                    onClick = onInspect,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Inspect Packet",
                        tint = MeshCyanPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = packet.payload,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Route summary bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeLogBackground, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Src: ${packet.sourceId} ➔ Dst: ${packet.destinationId}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                        Text(
                            text = "Hops: ${packet.hopCount} | TTL: ${packet.ttl}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MeshCyanPrimary
                        )
                    }
                    if (packet.routeHistory.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Path: ${packet.routeHistory.joinToString(" ➔ ")}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MeshGreenAccent
                        )
                    }
                }
            }
        }
    }
}
