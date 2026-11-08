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
    CHATS, CONVERSATION, COMPOSE, CREATE_GROUP, GROUP, NEARBY, SETTINGS, DIAGNOSTICS, EVENTS, PHYSICAL, PAIRING
}

/**
 * Immutable state representing the complete UI state for MeshLink Review III.
 */
data class MeshUiState(
    val currentNode: Node = Node(nodeId = "", nodeName = "Loading identity"),
    val rejectedEndpoints: Set<String> = emptySet(),
    val connectionProblem: Boolean = false,
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
    val selectedTab: MeshScreenTab = MeshScreenTab.CHATS,
    val selectedDestinationNodeIds: Set<String> = emptySet(),
    val connectNotice: String = "",
    val composeError: String = "",
    val messageInputText: String = "",
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
    val groups: List<com.meshlink.app.security.GroupChat> = emptyList(),
    val hiddenGroups: Set<String> = emptySet(),
    val leftGroups: Set<String> = emptySet(),
    val groupExits: Map<String, Map<String, com.meshlink.app.security.GroupExit>> = emptyMap(),
    val groupId: String = "",
    val groupName: String = "",
    val peerId: String = "",
    val unread: Set<Pair<String, String>> = emptySet(),
    val loadingHistory: Boolean = true
)
