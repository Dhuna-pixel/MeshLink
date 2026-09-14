import Foundation

public enum ForwardingDecision: Equatable, Sendable {
    case deliver(
        packet: MessagePacket,
        reason: String = "Destination reached: Current node matches packet destination"
    )

    case forward(
        updatedPacket: MessagePacket,
        candidateNextHopNodeIds: [String],
        reason: String = "Packet forwarded to next hop neighbor(s)"
    )

    case dropDuplicate(
        packetId: String,
        reason: String = "Duplicate packet detected: already processed"
    )

    case dropTtlExpired(
        packetId: String,
        currentTtl: Int,
        reason: String = "TTL expired: packet exceeded maximum allowed hop limit"
    )

    case dropNoRoute(
        packetId: String,
        destinationId: String,
        reason: String = "No route available: no suitable next-hop neighbor connected"
    )
}
