package com.meshlink.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.presentation.viewmodels.MeshUiState
import com.meshlink.app.ui.theme.CodeLogBackground
import com.meshlink.app.ui.theme.MeshAmberWarning
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshPurpleAccent
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@Composable
fun SimulationScreen(
    uiState: MeshUiState,
    onRunDemoClicked: () -> Unit,
    onRunCorruptDemoClicked: () -> Unit,
    onResetSimulation: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (uiState.isRunningSimulationAnimation) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MeshDarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "3-Node Multi-Hop & CRC Visualizer",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Text(
            text = "Demonstrates multi-hop forwarding (A ➔ B ➔ C) with CRC-16 integrity verification at each hop using pure domain RoutingEngine instances.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // TOPOLOGY VISUALIZER CARD
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VIRTUAL NETWORK TOPOLOGY",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 3-Node Topology Diagram
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Node A
                    SimNodeIcon(
                        label = "Node A",
                        sublabel = "SOURCE",
                        color = MeshCyanPrimary,
                        isHighlighted = uiState.isRunningSimulationAnimation
                    )

                    // Link A -> B
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = MeshCyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )

                    // Node B (Relay)
                    SimNodeIcon(
                        label = "Node B",
                        sublabel = "RELAY",
                        color = MeshGreenAccent,
                        isHighlighted = uiState.isRunningSimulationAnimation,
                        scale = pulseScale
                    )

                    // Link B -> C
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = MeshGreenAccent,
                        modifier = Modifier.size(20.dp)
                    )

                    // Node C (Destination)
                    SimNodeIcon(
                        label = "Node C",
                        sublabel = "DESTINATION",
                        color = MeshPurpleAccent,
                        isHighlighted = uiState.isRunningSimulationAnimation
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Live status step description
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CodeLogBackground, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = uiState.simulationStepDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshCyanPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onRunDemoClicked,
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isRunningSimulationAnimation,
                            colors = ButtonDefaults.buttonColors(containerColor = MeshGreenAccent, contentColor = MeshDarkBackground),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("A ➔ B ➔ C (CRC PASS)", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onResetSimulation,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    // Test CRC Error Injection Button
                    Button(
                        onClick = onRunCorruptDemoClicked,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isRunningSimulationAnimation,
                        colors = ButtonDefaults.buttonColors(containerColor = MeshAmberWarning, contentColor = MeshDarkBackground),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simulate CRC Error (Node B Rejection)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // REVIEW III ROUTING & CRC LOGIC REFERENCE
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MeshDarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Review III Multi-Hop Pipeline Reference",
                    style = MaterialTheme.typography.titleMedium,
                    color = MeshGreenAccent
                )
                Spacer(modifier = Modifier.height(8.dp))

                LogicStepRow(
                    stepNum = "1",
                    title = "Source Node A Originates Packet",
                    desc = "Creates packet (TTL: 5, Hops: 0, Route: [A]). Encodes frame & appends CRC-16-CCITT."
                )
                LogicStepRow(
                    stepNum = "2",
                    title = "Relay Node B Verifies CRC-16",
                    desc = "Reassembles frame, recalculates CRC-16. If mismatch ➔ DROP before routing. If pass ➔ continue."
                )
                LogicStepRow(
                    stepNum = "3",
                    title = "Relay Node B Mutates Hop & Route",
                    desc = "Checks duplicates. Mutates packet: TTL = 5 - 1 = 4, Hops = 0 + 1 = 1, Route: [A, B]."
                )
                LogicStepRow(
                    stepNum = "4",
                    title = "Relay Node B Generates NEW CRC-16",
                    desc = "Encodes forwarded packet into a NEW frame with a newly computed CRC-16 and transmits to Node C."
                )
                LogicStepRow(
                    stepNum = "5",
                    title = "Destination Node C Delivers Message",
                    desc = "Node C validates CRC-16 (PASS), mutates for final link (TTL = 3, Hops = 2, Route: [A, B, C]), and delivers to user."
                )
            }
        }
    }
}

@Composable
private fun SimNodeIcon(
    label: String,
    sublabel: String,
    color: Color,
    isHighlighted: Boolean,
    scale: Float = 1f
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.scale(scale)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color.copy(alpha = 0.2f), CircleShape)
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = sublabel,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun LogicStepRow(stepNum: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(MeshCyanPrimary.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = stepNum, color = MeshCyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontSize = 13.sp)
            Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, fontSize = 11.sp)
        }
    }
}
