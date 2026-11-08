package com.meshlink.app.domain.models

import kotlinx.serialization.Serializable

/**
 * Structured packet exchanged between MeshLink nodes across the peer-to-peer network.
 *
 * @param messageId Unique identifier for this message (e.g. "MSG-A1B2C3D4")
 * @param sourceId Node ID of the original sender/creator
 * @param sourceName Human-readable name of the original sender
 * @param destinationId Node ID of the intended ultimate recipient
 * @param destinationName Human-readable name of the intended recipient
 * @param payload The textual content / message data
 * @param ttl Time-To-Live hop limit to prevent infinite loops (decremented per hop traversal)
 * @param hopCount Number of hops this packet has traversed so far (incremented per hop traversal)
 * @param timestamp Epoch timestamp when the packet was created
 * @param routeHistory Chronological list of node IDs that have handled/traversed this packet
 * @param previousHopId Node ID of the immediate previous node that handled/transmitted this packet
 */
@Serializable
data class MessagePacket(
    val messageId: String,
    val sourceId: String,
    val sourceName: String = "",
    val destinationId: String,
    val destinationName: String = "",
    val payload: String,
    val ttl: Int = 5,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val routeHistory: List<String> = emptyList(),
    val previousHopId: String? = null,
    val control: com.meshlink.app.domain.routing.RouteControl? = null,
    val ackFor: String? = null,
    val returnPath: List<String> = emptyList(),
    val senderProfile: String? = null,
    val group: com.meshlink.app.security.GroupContext? = null
) {
    /**
     * Creates an updated clone of this packet when a link traversal into [receivingNodeId] occurs.
     * Decrements TTL by exactly 1, increments hopCount by exactly 1, records receiving node
     * in routeHistory, and sets previousHopId.
     */
    fun createHopPacket(receivingNodeId: String): MessagePacket {
        val updatedHistory = if (routeHistory.contains(receivingNodeId)) {
            routeHistory
        } else {
            routeHistory + receivingNodeId
        }
        return this.copy(
            ttl = this.ttl - 1,
            hopCount = this.hopCount + 1,
            routeHistory = updatedHistory,
            previousHopId = receivingNodeId
        )
    }

    /**
     * Backward-compatible alias for relay forwarding.
     */
    fun createForwardedPacket(relayNodeId: String): MessagePacket = createHopPacket(relayNodeId)

    /**
     * Helper to get a concise readable summary of the packet state.
     */
    fun getSummary(): String {
        return "ID: $messageId | Src: $sourceId -> Dst: $destinationId | TTL: $ttl | Hops: $hopCount | Route: ${routeHistory.joinToString(" -> ")}"
    }
}
