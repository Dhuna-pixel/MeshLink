package com.meshlink.app.domain.routing

import com.meshlink.app.domain.models.ForwardingDecision
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus

/**
 * Core Routing Engine for MeshLink (Review III).
 *
 * Implements decentralized multi-hop routing, duplicate detection, deterministic TTL/hop
 * semantics, and loop avoidance:
 *
 * A -> B -> C Flow:
 * - Source A creates local packet: TTL = 5, HopCount = 0, RouteHistory = [A]
 * - Node B (Relay) receives packet from A:
 *     1. Duplicate check: PASS
 *     2. Hop mutation (A -> B link): TTL = 4, HopCount = 1, RouteHistory = [A, B]
 *     3. Destination check: Node B != Node C (Relay required)
 *     4. TTL check: 4 >= 1 (Can forward)
 *     5. Next hop selection: Node C (Node A excluded via loop avoidance)
 *     6. Serializes updated packet, computes NEW CRC-16, and forwards to Node C
 * - Node C (Destination) receives packet from B:
 *     1. Duplicate check: PASS
 *     2. Hop mutation (B -> C link): TTL = 3, HopCount = 2, RouteHistory = [A, B, C]
 *     3. Destination check: Node C == Node C (DELIVER)
 *     4. Final delivered state at Node C: TTL = 3, HopCount = 2, Route = [A, B, C]
 *
 * Direct A -> C Flow:
 * - Source A: TTL = 5, HopCount = 0, Route = [A]
 * - Destination C: TTL = 4, HopCount = 1, Route = [A, C]
 *
 * Two-Relay A -> B -> C -> D Flow:
 * - Source A: TTL = 5, HopCount = 0, Route = [A]
 * - Relay B: TTL = 4, HopCount = 1, Route = [A, B]
 * - Relay C: TTL = 3, HopCount = 2, Route = [A, B, C]
 * - Destination D: TTL = 2, HopCount = 3, Route = [A, B, C, D]
 *
 * Architecture principle:
 * Pure Kotlin domain logic with 0 Android/BLE dependencies.
 */
class RoutingEngine(
    private val seenMessageManager: SeenMessageManager = SeenMessageManager()
) {

    /**
     * Processes an incoming message packet received from a neighbor or generated locally,
     * evaluating forwarding rules to produce a deterministic [ForwardingDecision].
     *
     * @param packet The incoming [MessagePacket]
     * @param currentNodeId The unique ID of the device running this engine
     * @param connectedNeighbors Currently active/connected neighbor nodes in the mesh
     * @return [ForwardingDecision] indicating Deliver, Forward, DropDuplicate, DropTtlExpired, or DropNoRoute
     */
    fun processIncomingPacket(
        packet: MessagePacket,
        currentNodeId: String,
        connectedNeighbors: List<Node>
    ): ForwardingDecision {

        // =========================================================================
        // STEP 1: DUPLICATE CHECK
        // Check if this node has already processed this exact messageId.
        // =========================================================================
        if (seenMessageManager.hasSeen(packet.messageId)) {
            return ForwardingDecision.DropDuplicate(
                packetId = packet.messageId,
                reason = "WARN [DROP][DUPLICATE] messageId=${packet.messageId} node=$currentNodeId. Dropping duplicate packet."
            )
        }

        // Mark as seen immediately
        seenMessageManager.markSeen(packet.messageId)

        // =========================================================================
        // STEP 2: HOP TRAVERSAL MUTATION
        // Exactly one link traversal has occurred into currentNodeId.
        // Decrement TTL by 1, increment hopCount by 1, append currentNodeId to routeHistory.
        // =========================================================================
        val mutatedPacket = packet.createHopPacket(receivingNodeId = currentNodeId)

        // =========================================================================
        // STEP 3: DESTINATION CHECK
        // If current node is the intended recipient, deliver with updated hop count & TTL.
        // =========================================================================
        if (packet.destinationId.equals(currentNodeId, ignoreCase = true)) {
            return ForwardingDecision.Deliver(
                packet = mutatedPacket,
                reason = "Destination reached! Node '$currentNodeId' is the intended recipient. Final TTL: ${mutatedPacket.ttl}, Hops: ${mutatedPacket.hopCount}, Path: ${mutatedPacket.routeHistory.joinToString(" -> ")}"
            )
        }

        // =========================================================================
        // STEP 4: TTL (TIME-TO-LIVE) CHECK ON RELAY
        // If mutated TTL < 1, packet cannot survive another outbound hop.
        // =========================================================================
        if (mutatedPacket.ttl < 1) {
            return ForwardingDecision.DropTtlExpired(
                packetId = packet.messageId,
                currentTtl = mutatedPacket.ttl,
                reason = "WARN [DROP][TTL] messageId=${packet.messageId} node=$currentNodeId ttl=${mutatedPacket.ttl} hopCount=${mutatedPacket.hopCount} routeHistory=${mutatedPacket.routeHistory.joinToString(" -> ")}"
            )
        }

        // =========================================================================
        // STEP 5: NEXT-HOP NEIGHBOR SELECTION & LOOP PREVENTION
        // Identify valid connected neighbors to forward the packet to.
        // =========================================================================
        val activeNeighbors = connectedNeighbors.filter { it.status == NodeStatus.CONNECTED }

        // Filter out:
        // 1. Current node itself
        // 2. Previous hop that transmitted to us (avoid immediate echo)
        // 3. All nodes in route history (avoid cycles)
        val excludedNodeIds = mutableSetOf(currentNodeId)
        packet.previousHopId?.let { excludedNodeIds.add(it) }
        excludedNodeIds.addAll(packet.routeHistory)

        val candidateNeighbors = activeNeighbors.filter { neighbor ->
            !excludedNodeIds.contains(neighbor.nodeId)
        }

        if (candidateNeighbors.isEmpty()) {
            return ForwardingDecision.DropNoRoute(
                packetId = packet.messageId,
                destinationId = packet.destinationId,
                reason = "WARN [DROP][NO_ROUTE] messageId=${packet.messageId} node=$currentNodeId destination=${packet.destinationId}. No suitable next-hop neighbor connected."
            )
        }

        // Priority: Direct destination if connected as neighbor
        val directDestinationNeighbor = candidateNeighbors.find {
            it.nodeId.equals(packet.destinationId, ignoreCase = true)
        }

        val targetNeighborIds = if (directDestinationNeighbor != null) {
            listOf(directDestinationNeighbor.nodeId)
        } else {
            candidateNeighbors.map { it.nodeId }
        }

        return ForwardingDecision.Forward(
            updatedPacket = mutatedPacket,
            candidateNextHopNodeIds = targetNeighborIds,
            reason = "Node '$currentNodeId' relaying packet '${packet.messageId}' to ${targetNeighborIds.joinToString()}. TTL: ${packet.ttl} -> ${mutatedPacket.ttl}, Hops: ${packet.hopCount} -> ${mutatedPacket.hopCount}"
        )
    }

    /**
     * Prepares an outgoing packet created locally at the source node.
     * Sets initial route history to include the source node.
     */
    fun createLocalPacket(
        messageId: String,
        sourceId: String,
        sourceName: String,
        destinationId: String,
        destinationName: String,
        payload: String,
        initialTtl: Int = 5
    ): MessagePacket {
        val packet = MessagePacket(
            messageId = messageId,
            sourceId = sourceId,
            sourceName = sourceName,
            destinationId = destinationId,
            destinationName = destinationName.ifEmpty { destinationId },
            payload = payload,
            ttl = initialTtl,
            hopCount = 0,
            timestamp = System.currentTimeMillis(),
            routeHistory = listOf(sourceId),
            previousHopId = sourceId
        )
        // Mark locally created message as seen so it won't echo back
        seenMessageManager.markSeen(messageId)
        return packet
    }

    /**
     * Clears seen message cache (useful for resetting session or tests).
     */
    fun resetSeenCache() {
        seenMessageManager.clear()
    }

    fun getSeenCount(): Int = seenMessageManager.size()
}
