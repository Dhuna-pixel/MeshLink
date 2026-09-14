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
 * @param ttl Time-To-Live hop limit to prevent infinite loops (decremented at each relay)
 * @param hopCount Number of hops this packet has traversed so far (incremented at each relay)
 * @param timestamp Epoch timestamp when the packet was created
 * @param routeHistory Chronological list of node IDs that have handled/relayed this packet
 * @param previousHopId Node ID of the immediate previous node that transmitted this packet
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
    val previousHopId: String? = null
) {
    /**
     * Creates an updated clone of this packet when an intermediate relay node forwards it.
     * Decrements TTL by 1, increments hopCount by 1, records current node in route history,
     * and sets previousHopId to current node.
     */
    fun createForwardedPacket(relayNodeId: String): MessagePacket {
        val updatedHistory = if (routeHistory.contains(relayNodeId)) {
            routeHistory
        } else {
            routeHistory + relayNodeId
        }
        return this.copy(
            ttl = this.ttl - 1,
            hopCount = this.hopCount + 1,
            routeHistory = updatedHistory,
            previousHopId = relayNodeId
        )
    }

    /**
     * Helper to get a concise readable summary of the packet state.
     */
    fun getSummary(): String {
        return "ID: $messageId | Src: $sourceId -> Dst: $destinationId | TTL: $ttl | Hops: $hopCount | Path: ${routeHistory.joinToString(" -> ")}"
    }
}
