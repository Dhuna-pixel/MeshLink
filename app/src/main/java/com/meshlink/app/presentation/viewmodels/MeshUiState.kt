package com.meshlink.app.presentation.viewmodels

import com.meshlink.app.data.repository.TransportMode
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node

/**
 * Screen navigation destinations in the bottom navigation bar.
 */
enum class MeshScreenTab {
    NODE_STATUS,
    NEIGHBORS,
    MESSAGING,
    LOGS,
    SIMULATION
}

/**
 * Immutable state representing the complete UI state for MeshLink.
 */
data class MeshUiState(
    val currentNode: Node = Node(nodeId = "NODE-A", nodeName = "Node A"),
    val isNetworkingActive: Boolean = false,
    val isSimulationMode: Boolean = false,
    val transportMode: TransportMode = TransportMode.NEARBY_CONNECTIONS,
    val discoveredNodes: List<Node> = emptyList(),
    val connectedNodes: List<Node> = emptyList(),
    val messages: List<MessagePacket> = emptyList(),
    val networkLogs: List<NetworkLogEntry> = emptyList(),
    val selectedTab: MeshScreenTab = MeshScreenTab.NODE_STATUS,
    val selectedDestinationNodeId: String = "",
    val messageInputText: String = "",
    val ttlSliderValue: Int = 5,
    val logFilterLevel: LogLevel? = null,
    val isInspectingPacket: MessagePacket? = null,
    val isEditingIdentityDialogShown: Boolean = false,
    val isRunningSimulationAnimation: Boolean = false,
    val simulationStepDescription: String = "Ready for test"
)
