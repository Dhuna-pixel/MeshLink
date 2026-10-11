package com.meshlink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.repository.TransportMode
import com.meshlink.app.presentation.viewmodels.MeshScreenTab
import com.meshlink.app.presentation.viewmodels.MeshViewModel
import com.meshlink.app.ui.components.NodeIdentityDialog
import com.meshlink.app.ui.components.PacketInspectorDialog
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshPurpleAccent
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MeshViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (uiState.isNetworkingActive) MeshGreenAccent else Color.Gray,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "MeshLink",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${uiState.currentNode.nodeName} [${uiState.currentNode.nodeId}]",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MeshCyanPrimary
                            )
                        }
                    }
                },
                actions = {
                    val (badgeText, badgeColor) = when (uiState.transportMode) {
                        TransportMode.BLE_GATT -> "BLE GATT" to MeshCyanPrimary
                        TransportMode.SIMULATION -> "SIM" to MeshPurpleAccent
                        TransportMode.NEARBY_CONNECTIONS -> "NEARBY" to MeshGreenAccent
                    }

                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = badgeColor
                        )
                    }

                    IconButton(onClick = { viewModel.showIdentityDialog(true) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configure Node",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MeshDarkSurface)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MeshDarkSurface,
                tonalElevation = 4.dp
            ) {
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MeshDarkBackground,
                    selectedTextColor = MeshCyanPrimary,
                    indicatorColor = MeshCyanPrimary,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == MeshScreenTab.NODE_STATUS,
                    onClick = { viewModel.selectTab(MeshScreenTab.NODE_STATUS) },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Home") },
                    label = { Text("Status", fontSize = 10.sp) },
                    colors = navItemColors
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == MeshScreenTab.NEIGHBORS,
                    onClick = { viewModel.selectTab(MeshScreenTab.NEIGHBORS) },
                    icon = { Icon(Icons.Default.CastConnected, contentDescription = "Neighbors") },
                    label = { Text("Neighbors (${uiState.connectedNodes.size})", fontSize = 10.sp) },
                    colors = navItemColors
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == MeshScreenTab.MESSAGING,
                    onClick = { viewModel.selectTab(MeshScreenTab.MESSAGING) },
                    icon = { Icon(Icons.Default.Mail, contentDescription = "Messaging") },
                    label = { Text("Messages", fontSize = 10.sp) },
                    colors = navItemColors
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == MeshScreenTab.LOGS,
                    onClick = { viewModel.selectTab(MeshScreenTab.LOGS) },
                    icon = { Icon(Icons.Default.Article, contentDescription = "Logs") },
                    label = { Text("Logs", fontSize = 10.sp) },
                    colors = navItemColors
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == MeshScreenTab.SIMULATION,
                    onClick = { viewModel.selectTab(MeshScreenTab.SIMULATION) },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = "3-Node Sim") },
                    label = { Text("3-Node Sim", fontSize = 10.sp) },
                    colors = navItemColors
                )
            }
        },
        containerColor = MeshDarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.selectedTab) {
                MeshScreenTab.NODE_STATUS -> HomeScreen(
                    uiState = uiState,
                    onToggleNetworking = { viewModel.toggleNetworking() },
                    onSelectTransportMode = { viewModel.setTransportMode(it) },
                    onEditIdentityClicked = { viewModel.showIdentityDialog(true) },
                    onNavigateToTab = { viewModel.selectTab(it) }
                )
                MeshScreenTab.NEIGHBORS -> NeighborsScreen(
                    uiState = uiState,
                    onConnectToNode = { viewModel.connectToNode(it) },
                    onDisconnectNode = { viewModel.disconnectNode(it) }
                )
                MeshScreenTab.MESSAGING -> MessagingScreen(
                    uiState = uiState,
                    onDestinationSelected = { viewModel.updateSelectedDestination(it) },
                    onMessageInputChanged = { viewModel.updateMessageInput(it) },
                    onTtlChanged = { viewModel.updateTtl(it) },
                    onToggleCrcCorruption = { viewModel.toggleCrcErrorSimulation() },
                    onSendMessage = { viewModel.sendMessage() },
                    onInspectPacket = { viewModel.inspectPacket(it) },
                    onClearMessages = { viewModel.clearMessages() }
                )
                MeshScreenTab.LOGS -> LogScreen(
                    uiState = uiState,
                    onFilterChanged = { viewModel.setLogFilter(it) },
                    onClearLogs = { viewModel.clearLogs() }
                )
                MeshScreenTab.SIMULATION -> SimulationScreen(
                    uiState = uiState,
                    onRunDemoClicked = { viewModel.runThreeNodeSimulationDemo(false) },
                    onRunCorruptDemoClicked = { viewModel.runThreeNodeSimulationDemo(true) },
                    onResetSimulation = { viewModel.setSimulationMode(true) }
                )
            }
        }

        // Dialogs
        if (uiState.isEditingIdentityDialogShown) {
            NodeIdentityDialog(
                initialNodeId = uiState.currentNode.nodeId,
                initialNodeName = uiState.currentNode.nodeName,
                onConfirm = { id, name -> viewModel.updateNodeIdentity(id, name) },
                onDismiss = { viewModel.showIdentityDialog(false) }
            )
        }

        uiState.isInspectingPacket?.let { packet ->
            PacketInspectorDialog(
                packet = packet,
                onDismiss = { viewModel.inspectPacket(null) }
            )
        }
    }
}
