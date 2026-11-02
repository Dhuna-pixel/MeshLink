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
    SETTINGS
}

/**
 * Immutable state representing the complete UI state for MeshLink Review III.
 */
data class MeshUiState(
    val currentNode: Node = Node(nodeId = "", nodeName = "Loading identity"),
    val bluetoothStatus: String = "Checking",
    val isNetworkingActive: Boolean = false,
    val transportMode: TransportMode = TransportMode.BLE_GATT,
    val discoveredNodes: List<Node> = emptyList(),
    val connectedNodes: List<Node> = emptyList(),
    val knownNodes: List<Node> = emptyList(),
    val routes: List<com.meshlink.app.domain.routing.AodvRoute> = emptyList(),
    val deliveryStatuses: Map<String, String> = emptyMap(),
    val messages: List<MessagePacket> = emptyList(),
    val networkLogs: List<NetworkLogEntry> = emptyList(),
    val selectedTab: MeshScreenTab = MeshScreenTab.NODE_STATUS,
    val selectedDestinationNodeIds: Set<String> = emptySet(),
    val messageInputText: String = "",
    val ttlSliderValue: Int = 5,
    val logFilterLevel: LogLevel? = null,
    val isInspectingPacket: MessagePacket? = null,
    val isEditingIdentityDialogShown: Boolean = false,
    val preferences: com.meshlink.app.data.preferences.AppPreferences = com.meshlink.app.data.preferences.AppPreferences(),
    val notificationSystemStatus: String = "Checking notification settings",
    val notificationTapMessage: String = "",
    val startupBusy: Boolean = false,
    val startupMessage: String = "",
    val demoTopology: com.meshlink.app.domain.network.DemoTopology = com.meshlink.app.domain.network.DemoTopology(),
    val demoMessage: String = "",
    val destinations: List<com.meshlink.app.domain.network.DestinationOption> = emptyList(),
    val isCrcErrorSimulationArmed: Boolean = false
)
