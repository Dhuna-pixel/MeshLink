import Foundation

/**
 * Result of the RoutingEngine evaluation when processing an incoming or locally generated packet.
 * Cleanly encapsulates all possible outcomes for deterministic handling matching Android's sealed class.
 */
public enum ForwardingDecision: Equatable, Sendable {

    /**
     * The current node IS the destination. The payload should be delivered to the local user.
     */
    case deliver(
        packet: MessagePacket,
        reason: String = "Destination reached: Current node matches packet destination"
    )

    /**
     * The current node is NOT the destination. The packet should be forwarded to the next hop(s).
     *
     * @param updatedPacket The modified packet with decremented TTL, incremented hop count, and updated route history
     * @param candidateNextHopNodeIds Node IDs of connected neighbors that should receive the forwarded packet
     * @param reason Explanation of why and where the packet is being forwarded
     */
    case forward(
        updatedPacket: MessagePacket,
        candidateNextHopNodeIds: [String],
        reason: String = "Packet forwarded to next hop neighbor(s)"
    )

    /**
     * Packet has already been processed by this node. Dropped to prevent loops and broadcast storms.
     */
    case dropDuplicate(
        packetId: String,
        reason: String = "Duplicate packet detected: already processed"
    )

    /**
     * Packet Time-To-Live has expired (TTL <= 1 before forwarding). Dropped to prevent eternal circulation.
     */
    case dropTtlExpired(
        packetId: String,
        currentTtl: Int,
        reason: String = "TTL expired: packet exceeded maximum allowed hop limit"
    )

    /**
     * Current node cannot forward the packet because no valid next hop neighbor is available.
     */
    case dropNoRoute(
        packetId: String,
        destinationId: String,
        reason: String = "No route available: no suitable next-hop neighbor connected"
    )
}
