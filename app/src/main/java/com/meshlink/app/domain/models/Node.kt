package com.meshlink.app.domain.models

import kotlinx.serialization.Serializable

/**
 * Represents the connection status of a node in the mesh network.
 */
enum class NodeStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * Represents a participating node/device in the MeshLink ad-hoc network.
 *
 * @param nodeId Unique persistent identifier for this device/node (e.g. "NODE-A" or UUID)
 * @param nodeName Human-readable name configured by user (e.g. "Node A (Alpha)")
 * @param status Current connection lifecycle status
 * @param endpointId Transport-specific identifier (e.g. Nearby Connections endpoint ID)
 * @param lastSeenTimestamp Epoch milliseconds when this node was last detected or communicated with
 * @param isRelayCapable Indicates if this node is willing to act as an intermediate multi-hop relay
 */
@Serializable
data class Node(
    val nodeId: String,
    val nodeName: String,
    val status: NodeStatus = NodeStatus.DISCOVERED,
    val endpointId: String? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isRelayCapable: Boolean = true
)
