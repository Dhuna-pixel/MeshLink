import Foundation

public final class RoutingEngine: @unchecked Sendable {
    private let seenMessageManager: SeenMessageManager

    public init(seenMessageManager: SeenMessageManager = SeenMessageManager()) {
        self.seenMessageManager = seenMessageManager
    }

    public func processIncomingPacket(
        packet: MessagePacket,
        currentNodeId: String,
        connectedNeighbors: [Node]
    ) -> ForwardingDecision {

        // STEP 1: DUPLICATE CHECK
        if seenMessageManager.hasSeen(packet.messageId) {
            return .dropDuplicate(
                packetId: packet.messageId,
                reason: "Message '\(packet.messageId)' has already been processed by node '\(currentNodeId)'. Dropping duplicate to prevent looping."
            )
        }

        seenMessageManager.markSeen(packet.messageId)

        // STEP 2: DESTINATION CHECK
        if packet.destinationId.caseInsensitiveCompare(currentNodeId) == .orderedSame {
            return .deliver(
                packet: packet,
                reason: "Destination reached! Current node '\(currentNodeId)' is the intended recipient. Message delivered."
            )
        }

        // STEP 3: TTL CHECK
        if packet.ttl <= 1 {
            return .dropTtlExpired(
                packetId: packet.messageId,
                currentTtl: packet.ttl,
                reason: "TTL expired for message '\(packet.messageId)' at relay node '\(currentNodeId)' (TTL was \(packet.ttl)). Packet dropped."
            )
        }

        // STEP 4: NEXT-HOP NEIGHBOR SELECTION & LOOP PREVENTION
        let activeNeighbors = connectedNeighbors.filter { $0.status == .connected }

        var excludedNodeIds = Set<String>([currentNodeId])
        if let prev = packet.previousHopId {
            excludedNodeIds.insert(prev)
        }
        excludedNodeIds.formUnion(packet.routeHistory)

        let candidateNeighbors = activeNeighbors.filter { neighbor in
            !excludedNodeIds.contains(neighbor.nodeId)
        }

        if candidateNeighbors.isEmpty {
            let activeList = activeNeighbors.map { $0.nodeId }.joined(separator: ", ")
            let excludedList = excludedNodeIds.joined(separator: ", ")
            return .dropNoRoute(
                packetId: packet.messageId,
                destinationId: packet.destinationId,
                reason: "No suitable next-hop neighbor available from '\(currentNodeId)' to reach '\(packet.destinationId)'. Active neighbors: [\(activeList)], Excluded: [\(excludedList)]"
            )
        }

        let directDestinationNeighbor = candidateNeighbors.first {
            $0.nodeId.caseInsensitiveCompare(packet.destinationId) == .orderedSame
        }

        let targetNeighborIds: [String]
        if let direct = directDestinationNeighbor {
            targetNeighborIds = [direct.nodeId]
        } else {
            targetNeighborIds = candidateNeighbors.map { $0.nodeId }
        }

        // STEP 5: PACKET TRANSFORMATION & FORWARDING
        let updatedPacket = packet.createForwardedPacket(relayNodeId: currentNodeId)

        let nextHopsStr = targetNeighborIds.joined(separator: ", ")
        return .forward(
            updatedPacket: updatedPacket,
            candidateNextHopNodeIds: targetNeighborIds,
            reason: "Node '\(currentNodeId)' acting as relay: forwarding '\(packet.messageId)' to next hop(s) \(nextHopsStr). TTL: \(packet.ttl) -> \(updatedPacket.ttl), Hops: \(packet.hopCount) -> \(updatedPacket.hopCount)"
        )
    }

    public func createLocalPacket(
        messageId: String,
        sourceId: String,
        sourceName: String = "",
        destinationId: String,
        destinationName: String = "",
        payload: String,
        initialTtl: Int = 5
    ) -> MessagePacket {
        let packet = MessagePacket(
            messageId: messageId,
            sourceId: sourceId,
            sourceName: sourceName,
            destinationId: destinationId,
            destinationName: destinationName,
            payload: payload,
            ttl: initialTtl,
            hopCount: 0,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            routeHistory: [sourceId],
            previousHopId: sourceId
        )
        seenMessageManager.markSeen(messageId)
        return packet
    }

    public func resetSeenCache() {
        seenMessageManager.clear()
    }

    public func getSeenCount() -> Int {
        return seenMessageManager.size()
    }
}
