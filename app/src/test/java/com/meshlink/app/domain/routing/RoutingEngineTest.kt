package com.meshlink.app.domain.routing

import com.meshlink.app.domain.models.ForwardingDecision
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying the 5 core Review II routing requirements:
 *
 * Test 1 — Destination reached (DELIVER)
 * Test 2 — Forward (FORWARD with TTL & Hop Count update)
 * Test 3 — Duplicate detection (DROP_DUPLICATE)
 * Test 4 — TTL expiration (DROP_TTL_EXPIRED)
 * Test 5 — Complete Three-Node multi-hop flow (A -> B -> C)
 */
class RoutingEngineTest {

    private lateinit var routingEngine: RoutingEngine

    @Before
    fun setUp() {
        routingEngine = RoutingEngine(SeenMessageManager())
    }

    /**
     * Test 1 — Destination reached
     * When a packet arrives and packet.destinationId == currentNodeId,
     * the engine MUST return ForwardingDecision.Deliver.
     */
    @Test
    fun test1_destinationReached_deliversPayload() {
        val packet = MessagePacket(
            messageId = "MSG-001",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Hello Destination Node C",
            ttl = 5,
            hopCount = 1,
            routeHistory = listOf("NODE-A", "NODE-B"),
            previousHopId = "NODE-B"
        )

        val neighborsOfC = listOf(
            Node(nodeId = "NODE-B", nodeName = "Node B", status = NodeStatus.CONNECTED)
        )

        val decision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-C",
            connectedNeighbors = neighborsOfC
        )

        assertTrue("Decision should be Deliver", decision is ForwardingDecision.Deliver)
        val deliverDecision = decision as ForwardingDecision.Deliver
        assertEquals("NODE-C", deliverDecision.packet.destinationId)
        assertEquals("Hello Destination Node C", deliverDecision.packet.payload)
    }

    /**
     * Test 2 — Forward
     * When destination != currentNodeId and TTL > 1,
     * the engine MUST return ForwardingDecision.Forward with:
     * - TTL decremented (e.g. 5 -> 4)
     * - hopCount incremented (e.g. 0 -> 1)
     * - current node appended to routeHistory
     */
    @Test
    fun test2_relayNode_forwardsPacket_andUpdatesTtlAndHops() {
        val packet = MessagePacket(
            messageId = "MSG-002",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Multi-hop payload",
            ttl = 5,
            hopCount = 0,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

        val neighborsOfB = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED),
            Node(nodeId = "NODE-C", nodeName = "Node C", status = NodeStatus.CONNECTED)
        )

        val decision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue("Decision should be Forward", decision is ForwardingDecision.Forward)
        val forwardDecision = decision as ForwardingDecision.Forward

        // Verify packet header mutations
        assertEquals(4, forwardDecision.updatedPacket.ttl)
        assertEquals(1, forwardDecision.updatedPacket.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), forwardDecision.updatedPacket.routeHistory)
        assertEquals("NODE-B", forwardDecision.updatedPacket.previousHopId)

        // Verify loop avoidance: Node A (source & previous hop) excluded, Node C selected as target
        assertEquals(listOf("NODE-C"), forwardDecision.candidateNextHopNodeIds)
    }

    /**
     * Test 3 — Duplicate detection
     * If the same messageId is received a second time,
     * the engine MUST return ForwardingDecision.DropDuplicate.
     */
    @Test
    fun test3_duplicatePacket_dropped() {
        val packet = MessagePacket(
            messageId = "MSG-003",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Duplicate test payload",
            ttl = 5,
            hopCount = 0,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

        val neighbors = listOf(
            Node(nodeId = "NODE-C", nodeName = "Node C", status = NodeStatus.CONNECTED)
        )

        // First processing -> Should forward
        val firstDecision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighbors
        )
        assertTrue(firstDecision is ForwardingDecision.Forward)

        // Second processing with identical messageId -> Must be dropped as duplicate
        val secondDecision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighbors
        )
        assertTrue("Second receipt must be dropped as duplicate", secondDecision is ForwardingDecision.DropDuplicate)
    }

    /**
     * Test 4 — TTL expired
     * When TTL <= 1, forwarding would exceed hop limit,
     * the engine MUST return ForwardingDecision.DropTtlExpired.
     */
    @Test
    fun test4_expiredTtl_dropped() {
        val packetWithTtl1 = MessagePacket(
            messageId = "MSG-004",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Expired test payload",
            ttl = 1, // TTL = 1 cannot be forwarded further
            hopCount = 4,
            routeHistory = listOf("NODE-A", "NODE-X", "NODE-Y", "NODE-Z"),
            previousHopId = "NODE-Z"
        )

        val neighbors = listOf(
            Node(nodeId = "NODE-C", nodeName = "Node C", status = NodeStatus.CONNECTED)
        )

        val decision = routingEngine.processIncomingPacket(
            packet = packetWithTtl1,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighbors
        )

        assertTrue("Decision should be DropTtlExpired", decision is ForwardingDecision.DropTtlExpired)
    }

    /**
     * Test 5 — Complete Three-Node multi-hop flow (A -> B -> C)
     * Simulates end-to-end:
     * 1. Node A creates packet for Node C
     * 2. Node B (Relay) processes and forwards to Node C
     * 3. Node C (Destination) receives and delivers
     */
    @Test
    fun test5_threeNodeMultiHopFlow_success() {
        val engineA = RoutingEngine()
        val engineB = RoutingEngine()
        val engineC = RoutingEngine()

        // 1. Node A creates local packet
        val packetFromA = engineA.createLocalPacket(
            messageId = "MSG-FLOW-100",
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-C",
            destinationName = "Node C",
            payload = "End-to-End MeshLink Test",
            initialTtl = 5
        )

        assertEquals("NODE-A", packetFromA.sourceId)
        assertEquals("NODE-C", packetFromA.destinationId)
        assertEquals(5, packetFromA.ttl)
        assertEquals(0, packetFromA.hopCount)
        assertEquals(listOf("NODE-A"), packetFromA.routeHistory)

        // 2. Node B (Relay) receives packet from Node A
        val neighborsOfB = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED),
            Node(nodeId = "NODE-C", nodeName = "Node C", status = NodeStatus.CONNECTED)
        )

        val decisionAtB = engineB.processIncomingPacket(
            packet = packetFromA,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue("Node B must forward the packet", decisionAtB is ForwardingDecision.Forward)
        val forwardedPacketByB = (decisionAtB as ForwardingDecision.Forward).updatedPacket

        assertEquals(4, forwardedPacketByB.ttl)
        assertEquals(1, forwardedPacketByB.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), forwardedPacketByB.routeHistory)

        // 3. Node C (Destination) receives forwarded packet from Node B
        val neighborsOfC = listOf(
            Node(nodeId = "NODE-B", nodeName = "Node B", status = NodeStatus.CONNECTED)
        )

        val decisionAtC = engineC.processIncomingPacket(
            packet = forwardedPacketByB,
            currentNodeId = "NODE-C",
            connectedNeighbors = neighborsOfC
        )

        assertTrue("Node C must deliver the packet", decisionAtC is ForwardingDecision.Deliver)
        val deliveredPacket = (decisionAtC as ForwardingDecision.Deliver).packet

        assertEquals("NODE-A", deliveredPacket.sourceId)
        assertEquals("NODE-C", deliveredPacket.destinationId)
        assertEquals("End-to-End MeshLink Test", deliveredPacket.payload)
        assertEquals(1, deliveredPacket.hopCount)
        assertEquals(4, deliveredPacket.ttl)
        assertEquals(listOf("NODE-A", "NODE-B"), deliveredPacket.routeHistory)
    }

    /**
     * Test 6 — Loop Avoidance
     * Verifies that neighbors already in route history are excluded from candidate next hops.
     */
    @Test
    fun test6_loopAvoidance_excludesRouteHistoryAndPreviousHop() {
        val packet = MessagePacket(
            messageId = "MSG-006",
            sourceId = "NODE-A",
            destinationId = "NODE-D",
            payload = "Loop test",
            ttl = 5,
            hopCount = 2,
            routeHistory = listOf("NODE-A", "NODE-X"),
            previousHopId = "NODE-X"
        )

        // Node B has connected neighbors: Node A (in history), Node X (previous hop), Node Y (new valid neighbor)
        val neighborsOfB = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED),
            Node(nodeId = "NODE-X", nodeName = "Node X", status = NodeStatus.CONNECTED),
            Node(nodeId = "NODE-Y", nodeName = "Node Y", status = NodeStatus.CONNECTED)
        )

        val decision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue(decision is ForwardingDecision.Forward)
        val forward = decision as ForwardingDecision.Forward
        assertEquals(listOf("NODE-Y"), forward.candidateNextHopNodeIds)
    }

    /**
     * Test 7 — No route available
     * When no valid unvisited connected neighbors exist, engine returns DropNoRoute.
     */
    @Test
    fun test7_noRouteAvailable_returnsDropNoRoute() {
        val packet = MessagePacket(
            messageId = "MSG-007",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Dead end test",
            ttl = 5,
            hopCount = 1,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

        // Only neighbor is Node A (previous hop)
        val neighborsOfB = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED)
        )

        val decision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue("Should drop when no next hop is available", decision is ForwardingDecision.DropNoRoute)
    }
}
