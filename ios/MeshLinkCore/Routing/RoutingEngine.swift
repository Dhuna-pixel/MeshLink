import Foundation

/**
 * Core Routing Engine for MeshLink.
 *
 * Implements the decentralized multi-hop forwarding and delivery logic:
 *
 * A -> B -> C flow:
 * 1. Duplicate detection: Drops already processed message IDs to prevent broadcast storms / loops.
 * 2. Destination matching: If this node is the destination, deliver packet to user.
 * 3. TTL validation: If TTL <= 1, drops packet due to hop expiration.
 * 4. Next-Hop Selection: Identifies connected neighbors (excluding previous hop and route history to prevent loops).
 *    If direct destination is connected, targets that node; otherwise forwards to available neighbor(s).
 * 5. Packet Transformation: Decrements TTL, increments hop count, updates route history and previousHopId.
 *
 * Architecture principle:
 * This class is 100% pure domain logic and contains zero UI/hardware dependencies.
 * It is easily testable and 100% wire- and logic-compatible with Android MeshLink.
 */
public final class RoutingEngine: @unchecked Sendable {
    private let seenMessageManager: SeenMessageManager

    public init(seenMessageManager: SeenMessageManager = SeenMessageManager()) {
        self.seenMessageManager = seenMessageManager
    }

    /**
     * Processes an incoming message packet received from a neighbor or generated locally,
     * evaluating forwarding rules to produce a deterministic `ForwardingDecision`.
     *
     * @param packet The incoming `MessagePacket`
     * @param currentNodeId The unique ID of the device running this engine
     * @param connectedNeighbors Currently active/connected neighbor nodes in the mesh
     * @return `ForwardingDecision` indicating Deliver, Forward, DropDuplicate, DropTtlExpired, or DropNoRoute
     */
    public func processIncomingPacket(
        packet: MessagePacket,
        currentNodeId: String,
        connectedNeighbors: [Node]
    ) -> ForwardingDecision {

        // =========================================================================
        // STEP 1: DUPLICATE CHECK
        // Check if this node has already processed this exact messageId.
        // =========================================================================
        if seenMessageManager.hasSeen(packet.messageId) {
            return .dropDuplicate(
                packetId: packet.messageId,
                reason: "Message '\(packet.messageId)' has already been processed by node '\(currentNodeId)'. Dropping duplicate to prevent looping."
            )
        }

        // Mark as seen immediately so future occurrences are discarded
        seenMessageManager.markSeen(packet.messageId)

        // =========================================================================
        // STEP 2: DESTINATION CHECK
        // Check if current node is the intended recipient.
        // =========================================================================
        if packet.destinationId.caseInsensitiveCompare(currentNodeId) == .orderedSame {
            return .deliver(
                packet: packet,
                reason: "Destination reached! Current node '\(currentNodeId)' is the intended recipient. Message delivered."
            )
        }

        // =========================================================================
        // STEP 3: TTL (TIME-TO-LIVE) CHECK
        // If TTL <= 1, decrementing will result in 0 or less, which means the packet has expired.
        // =========================================================================
        if packet.ttl <= 1 {
            return .dropTtlExpired(
                packetId: packet.messageId,
                currentTtl: packet.ttl,
                reason: "TTL expired for message '\(packet.messageId)' at relay node '\(currentNodeId)' (TTL was \(packet.ttl)). Packet dropped."
            )
        }

        // =========================================================================
        // STEP 4: NEXT-HOP NEIGHBOR SELECTION & LOOP PREVENTION
        // Identify valid connected neighbors to forward the packet to.
        // =========================================================================
        let activeNeighbors = connectedNeighbors.filter { $0.status == .connected }

        // Filter out:
        // 1. The previous hop that just sent us the packet (avoid immediate echo)
        // 2. Any node already in the route history (avoid cyclical loops)
        // 3. Current node itself
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

        // Priority 1: Check if the ultimate destination is directly connected as an active neighbor
        let directDestinationNeighbor = candidateNeighbors.first {
            $0.nodeId.caseInsensitiveCompare(packet.destinationId) == .orderedSame
        }

        let targetNeighborIds: [String]
        if let direct = directDestinationNeighbor {
            targetNeighborIds = [direct.nodeId]
        } else {
            // Forward to candidate neighbors (controlled neighbor relay)
            targetNeighborIds = candidateNeighbors.map { $0.nodeId }
        }

        // =========================================================================
        // STEP 5: PACKET TRANSFORMATION & FORWARDING
        // Decrement TTL, increment hop count, record current node in route history.
        // =========================================================================
        let updatedPacket = packet.createForwardedPacket(relayNodeId: currentNodeId)

        let nextHopsStr = targetNeighborIds.joined(separator: ", ")
        return .forward(
            updatedPacket: updatedPacket,
            candidateNextHopNodeIds: targetNeighborIds,
            reason: "Node '\(currentNodeId)' acting as relay: forwarding '\(packet.messageId)' to next hop(s) \(nextHopsStr). TTL: \(packet.ttl) -> \(updatedPacket.ttl), Hops: \(packet.hopCount) -> \(updatedPacket.hopCount)"
        )
    }

    /**
     * Prepares an outgoing packet created locally at the source node.
     * Sets initial route history to include the source node.
     */
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
        // Mark locally created message as seen so it won't echo back
        seenMessageManager.markSeen(messageId)
        return packet
    }

    /**
     * Clears seen message cache (useful for resetting session or tests).
     */
    public func resetSeenCache() {
        seenMessageManager.clear()
    }

    public func getSeenCount() -> Int {
        return seenMessageManager.size()
    }
}
