package com.meshlink.app.domain.models

/**
 * Result of the RoutingEngine evaluation when processing an incoming or locally generated packet.
 * This sealed class cleanly encapsulates all possible outcomes for deterministic handling.
 */
sealed class ForwardingDecision {

    /**
     * The current node IS the destination. The payload should be delivered to the local user.
     */
    data class Deliver(
        val packet: MessagePacket,
        val reason: String = "Destination reached: Current node matches packet destination"
    ) : ForwardingDecision()

    /**
     * The current node is NOT the destination. The packet should be forwarded to the next hop(s).
     *
     * @param updatedPacket The modified packet with decremented TTL, incremented hop count, and updated route history
     * @param candidateNextHopNodeIds Node IDs of connected neighbors that should receive the forwarded packet
     * @param reason Explanation of why and where the packet is being forwarded
     */
    data class Forward(
        val updatedPacket: MessagePacket,
        val candidateNextHopNodeIds: List<String>,
        val reason: String = "Packet forwarded to next hop neighbor(s)"
    ) : ForwardingDecision()

    /**
     * Packet has already been processed by this node. Dropped to prevent loops and broadcast storms.
     */
    data class DropDuplicate(
        val packetId: String,
        val reason: String = "Duplicate packet detected: already processed"
    ) : ForwardingDecision()

    /**
     * Packet Time-To-Live has expired (TTL <= 0). Dropped to prevent eternal circulation.
     */
    data class DropTtlExpired(
        val packetId: String,
        val currentTtl: Int,
        val reason: String = "TTL expired: packet exceeded maximum allowed hop limit"
    ) : ForwardingDecision()

    /**
     * Current node cannot forward the packet because no valid next hop neighbor is available.
     */
    data class DropNoRoute(
        val packetId: String,
        val destinationId: String,
        val reason: String = "No route available: no suitable next-hop neighbor connected"
    ) : ForwardingDecision()
}
