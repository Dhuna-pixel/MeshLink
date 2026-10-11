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
 * Unit tests verifying Review III routing semantics:
 *
 * Test 1 — Direct destination reached (A -> C): Delivered with TTL=4, Hops=1, Route=[A, C]
 * Test 2 — Intermediate Relay forwarding (A -> B -> C): Relay B updates TTL=4, Hops=1, Route=[A, B]
 * Test 3 — Complete 3-Node multi-hop flow (A -> B -> C): Final Destination C delivers with TTL=3, Hops=2, Route=[A, B, C]
 * Test 4 — Two-Relay multi-hop flow (A -> B -> C -> D): Final Destination D delivers with TTL=2, Hops=3, Route=[A, B, C, D]
 * Test 5 — Duplicate detection (DropDuplicate)
 * Test 6 — TTL expiration (DropTtlExpired)
 * Test 7 — Loop avoidance (excludes route history and previous hop)
 * Test 8 — No route available (DropNoRoute)
 */
class RoutingEngineTest {

    private lateinit var routingEngine: RoutingEngine

    @Before
    fun setUp() {
        routingEngine = RoutingEngine(SeenMessageManager())
    }

    /**
     * Test 1 — Direct message from A to C (1 hop link traversal).
     * Source A creates: TTL = 5, HopCount = 0, Route = [A].
     * Destination C receives: Delivered with TTL = 4, HopCount = 1, Route = [A, C].
     */
    @Test
    fun test1_directMessage_deliversWithUpdatedHopsAndTtl() {
        val packetFromA = routingEngine.createLocalPacket(
            messageId = "MSG-DIRECT-001",
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-C",
            destinationName = "Node C",
            payload = "Direct BLE Message",
            initialTtl = 5
        )

        val neighborsOfC = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED)
        )

        val engineC = RoutingEngine()
        val decision = engineC.processIncomingPacket(
            packet = packetFromA,
            currentNodeId = "NODE-C",
            connectedNeighbors = neighborsOfC
        )

        assertTrue("Decision must be Deliver", decision is ForwardingDecision.Deliver)
        val delivered = (decision as ForwardingDecision.Deliver).packet

        assertEquals("NODE-A", delivered.sourceId)
        assertEquals("NODE-C", delivered.destinationId)
        assertEquals("Direct BLE Message", delivered.payload)
        assertEquals(4, delivered.ttl)
        assertEquals(1, delivered.hopCount)
        assertEquals(listOf("NODE-A", "NODE-C"), delivered.routeHistory)
    }

    /**
     * Test 2 — Relay Node B processes packet from Node A destined for Node C.
     * Before B: TTL = 5, HopCount = 0, Route = [A].
     * After B forwards: TTL = 4, HopCount = 1, Route = [A, B].
     */
    @Test
    fun test2_relayNodeB_forwardsPacket_andUpdatesTtlAndHops() {
        val packetFromA = MessagePacket(
            messageId = "MSG-RELAY-002",
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-C",
            destinationName = "Node C",
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
            packet = packetFromA,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue("Decision must be Forward", decision is ForwardingDecision.Forward)
        val forwardDecision = decision as ForwardingDecision.Forward

        // Verify packet header mutations for link A -> B
        assertEquals(4, forwardDecision.updatedPacket.ttl)
        assertEquals(1, forwardDecision.updatedPacket.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), forwardDecision.updatedPacket.routeHistory)
        assertEquals("NODE-B", forwardDecision.updatedPacket.previousHopId)

        // Verify loop avoidance: Node A (source & previous hop) excluded, Node C selected as target
        assertEquals(listOf("NODE-C"), forwardDecision.candidateNextHopNodeIds)
    }

    /**
     * Test 3 — Complete Three-Node multi-hop flow: A -> B -> C.
     *
     * 1. Source A: TTL = 5, HopCount = 0, Route = [A]
     * 2. After A -> B link: Relay B mutates to TTL = 4, HopCount = 1, Route = [A, B] and forwards
     * 3. After B -> C link: Destination C delivers with TTL = 3, HopCount = 2, Route = [A, B, C]
     */
    @Test
    fun test3_completeThreeNodeFlow_A_B_C_deliversWithTtl3AndHops2() {
        val engineA = RoutingEngine()
        val engineB = RoutingEngine()
        val engineC = RoutingEngine()

        // 1. Source A creates local packet
        val packetFromA = engineA.createLocalPacket(
            messageId = "MSG-FLOW-100",
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-C",
            destinationName = "Node C",
            payload = "End-to-End MeshLink Multi-Hop Test",
            initialTtl = 5
        )

        assertEquals(5, packetFromA.ttl)
        assertEquals(0, packetFromA.hopCount)
        assertEquals(listOf("NODE-A"), packetFromA.routeHistory)

        // 2. Node B (Relay) receives from A
        val neighborsOfB = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED),
            Node(nodeId = "NODE-C", nodeName = "Node C", status = NodeStatus.CONNECTED)
        )

        val decisionAtB = engineB.processIncomingPacket(
            packet = packetFromA,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue("Node B must forward", decisionAtB is ForwardingDecision.Forward)
        val forwardedPacketByB = (decisionAtB as ForwardingDecision.Forward).updatedPacket

        assertEquals(4, forwardedPacketByB.ttl)
        assertEquals(1, forwardedPacketByB.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), forwardedPacketByB.routeHistory)

        // 3. Node C (Destination) receives from Node B
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
        assertEquals("End-to-End MeshLink Multi-Hop Test", deliveredPacket.payload)
        assertEquals(3, deliveredPacket.ttl)
        assertEquals(2, deliveredPacket.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C"), deliveredPacket.routeHistory)
    }

    /**
     * Test 4 — Two-Relay multi-hop flow: A -> B -> C -> D.
     * Expected final D: TTL = 2, HopCount = 3, Route = [A, B, C, D]
     */
    @Test
    fun test4_twoRelayFlow_A_B_C_D_deliversWithTtl2AndHops3() {
        val engineA = RoutingEngine()
        val engineB = RoutingEngine()
        val engineC = RoutingEngine()
        val engineD = RoutingEngine()

        // 1. Source A
        val packetA = engineA.createLocalPacket(
            messageId = "MSG-4NODE-001",
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-D",
            destinationName = "Node D",
            payload = "4-Node 2-Relay Test",
            initialTtl = 5
        )

        // 2. Relay B
        val decisionB = engineB.processIncomingPacket(
            packet = packetA,
            currentNodeId = "NODE-B",
            connectedNeighbors = listOf(Node("NODE-A"), Node("NODE-C"))
        )
        assertTrue(decisionB is ForwardingDecision.Forward)
        val packetB = (decisionB as ForwardingDecision.Forward).updatedPacket
        assertEquals(4, packetB.ttl)
        assertEquals(1, packetB.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), packetB.routeHistory)

        // 3. Relay C
        val decisionC = engineC.processIncomingPacket(
            packet = packetB,
            currentNodeId = "NODE-C",
            connectedNeighbors = listOf(Node("NODE-B"), Node("NODE-D"))
        )
        assertTrue(decisionC is ForwardingDecision.Forward)
        val packetC = (decisionC as ForwardingDecision.Forward).updatedPacket
        assertEquals(3, packetC.ttl)
        assertEquals(2, packetC.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C"), packetC.routeHistory)

        // 4. Destination D
        val decisionD = engineD.processIncomingPacket(
            packet = packetC,
            currentNodeId = "NODE-D",
            connectedNeighbors = listOf(Node("NODE-C"))
        )
        assertTrue(decisionD is ForwardingDecision.Deliver)
        val delivered = (decisionD as ForwardingDecision.Deliver).packet
        assertEquals(2, delivered.ttl)
        assertEquals(3, delivered.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C", "NODE-D"), delivered.routeHistory)
    }

    /**
     * Test 5 — Duplicate detection.
     */
    @Test
    fun test5_duplicatePacket_dropped() {
        val packet = MessagePacket(
            messageId = "MSG-DUP-005",
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

        val firstDecision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighbors
        )
        assertTrue(firstDecision is ForwardingDecision.Forward)

        val secondDecision = routingEngine.processIncomingPacket(
            packet = packet,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighbors
        )
        assertTrue("Second receipt must be dropped as duplicate", secondDecision is ForwardingDecision.DropDuplicate)
    }

    /**
     * Test 6 — TTL expired.
     */
    @Test
    fun test6_expiredTtl_dropped() {
        val packetWithTtl1 = MessagePacket(
            messageId = "MSG-EXP-006",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Expired test payload",
            ttl = 1,
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
     * Test 7 — Loop Avoidance.
     */
    @Test
    fun test7_loopAvoidance_excludesRouteHistoryAndPreviousHop() {
        val packet = MessagePacket(
            messageId = "MSG-LOOP-007",
            sourceId = "NODE-A",
            destinationId = "NODE-D",
            payload = "Loop test",
            ttl = 5,
            hopCount = 2,
            routeHistory = listOf("NODE-A", "NODE-X"),
            previousHopId = "NODE-X"
        )

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
     * Test 8 — No route available.
     */
    @Test
    fun test8_noRouteAvailable_returnsDropNoRoute() {
        val packet = MessagePacket(
            messageId = "MSG-NOROUTE-008",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Dead end test",
            ttl = 5,
            hopCount = 1,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

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

    /**
     * Test 9 — Exact Physical BLE Relay Sequence Reproducer:
     * Message ID: MSG-B43572
     * Pipeline: Node A (Source) -> Node B (Relay) -> Node C (Destination)
     *
     * Verifies:
     * 1. Node A creates local packet: TTL=5, Hop=0, RouteHistory=[NODE-A]
     * 2. Node A encodes wire frame with CRC-16-CCITT
     * 3. Node B receives wire frame, validates CRC-16, parses packet
     * 4. Node B RoutingEngine mutates packet for A->B link: TTL=4, Hop=1, RouteHistory=[NODE-A, NODE-B]
     * 5. Node B encodes updated packet into wire frame with fresh CRC-16
     * 6. Node C receives wire frame from B, validates CRC-16, parses packet containing B's mutated state
     * 7. Node C RoutingEngine mutates packet for B->C link before destination delivery: TTL=3, Hop=2, RouteHistory=[NODE-A, NODE-B, NODE-C]
     * 8. Destination check matches Node C -> Deliver decision with exact state (TTL=3, Hop=2, RouteHistory=[NODE-A, NODE-B, NODE-C])
     * 9. Confirms no double mutation and no skipped incoming-link mutation.
     */
    @Test
    fun test9_physicalBleRelayExactSequence_A_B_C_MSG_B43572() {
        val engineA = RoutingEngine()
        val engineB = RoutingEngine()
        val engineC = RoutingEngine()

        val messageId = "MSG-B43572"
        val payloadText = "Physical BLE Relay Verification Payload"

        // -------------------------------------------------------------
        // Step 1: Node A creates local packet
        // -------------------------------------------------------------
        val packetAtA = engineA.createLocalPacket(
            messageId = messageId,
            sourceId = "NODE-A",
            sourceName = "Node A",
            destinationId = "NODE-C",
            destinationName = "Node C",
            payload = payloadText,
            initialTtl = 5
        )

        assertEquals("MSG-B43572", packetAtA.messageId)
        assertEquals(5, packetAtA.ttl)
        assertEquals(0, packetAtA.hopCount)
        assertEquals(listOf("NODE-A"), packetAtA.routeHistory)
        assertEquals("NODE-A", packetAtA.previousHopId)

        // -------------------------------------------------------------
        // Step 2: Node A encodes wire frame for Link A -> B
        // -------------------------------------------------------------
        val jsonFromA = com.meshlink.app.utils.JsonSerializer.packetToJson(packetAtA)
        val wireFrameAtoB = com.meshlink.app.data.transport.framing.MeshLinkFrame.encode(
            frameType = com.meshlink.app.data.transport.framing.MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = jsonFromA.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )

        // -------------------------------------------------------------
        // Step 3: Node B receives wire frame and validates CRC-16
        // -------------------------------------------------------------
        val reassemblerB = com.meshlink.app.data.transport.framing.FrameReassembler()
        val resultsB = reassemblerB.processChunk(wireFrameAtoB)
        assertEquals(1, resultsB.size)
        assertTrue("Node B must validate CRC successfully", resultsB[0] is com.meshlink.app.data.transport.framing.MeshLinkFrame.FrameResult.Success)

        val successB = resultsB[0] as com.meshlink.app.data.transport.framing.MeshLinkFrame.FrameResult.Success
        val parsedPacketAtB = com.meshlink.app.utils.JsonSerializer.jsonToPacket(
            String(successB.payload, java.nio.charset.StandardCharsets.UTF_8)
        )
        org.junit.Assert.assertNotNull(parsedPacketAtB)

        // -------------------------------------------------------------
        // Step 4: Node B RoutingEngine processes incoming packet (Link A -> B)
        // -------------------------------------------------------------
        val neighborsOfB = listOf(
            Node(nodeId = "NODE-A", nodeName = "Node A", status = NodeStatus.CONNECTED),
            Node(nodeId = "NODE-C", nodeName = "Node C", status = NodeStatus.CONNECTED)
        )
        val decisionAtB = engineB.processIncomingPacket(
            packet = parsedPacketAtB!!,
            currentNodeId = "NODE-B",
            connectedNeighbors = neighborsOfB
        )

        assertTrue("Node B decision must be Forward", decisionAtB is ForwardingDecision.Forward)
        val forwardDecision = decisionAtB as ForwardingDecision.Forward
        val packetForwardedByB = forwardDecision.updatedPacket

        // Verify Node B mutated state
        assertEquals("MSG-B43572", packetForwardedByB.messageId)
        assertEquals("NODE-A", packetForwardedByB.sourceId)
        assertEquals("NODE-C", packetForwardedByB.destinationId)
        assertEquals(4, packetForwardedByB.ttl)
        assertEquals(1, packetForwardedByB.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), packetForwardedByB.routeHistory)
        assertEquals("NODE-B", packetForwardedByB.previousHopId)
        assertEquals(listOf("NODE-C"), forwardDecision.candidateNextHopNodeIds)

        // -------------------------------------------------------------
        // Step 5: Node B encodes forwarded packet into wire frame for Link B -> C
        // -------------------------------------------------------------
        val jsonFromB = com.meshlink.app.utils.JsonSerializer.packetToJson(packetForwardedByB)
        val wireFrameBtoC = com.meshlink.app.data.transport.framing.MeshLinkFrame.encode(
            frameType = com.meshlink.app.data.transport.framing.MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = jsonFromB.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )

        // -------------------------------------------------------------
        // Step 6: Node C receives wire frame and validates CRC-16
        // -------------------------------------------------------------
        val reassemblerC = com.meshlink.app.data.transport.framing.FrameReassembler()
        val resultsC = reassemblerC.processChunk(wireFrameBtoC)
        assertEquals(1, resultsC.size)
        assertTrue("Node C must validate CRC successfully", resultsC[0] is com.meshlink.app.data.transport.framing.MeshLinkFrame.FrameResult.Success)

        val successC = resultsC[0] as com.meshlink.app.data.transport.framing.MeshLinkFrame.FrameResult.Success
        val parsedPacketAtC = com.meshlink.app.utils.JsonSerializer.jsonToPacket(
            String(successC.payload, java.nio.charset.StandardCharsets.UTF_8)
        )
        org.junit.Assert.assertNotNull(parsedPacketAtC)

        // Confirm incoming wire packet at C has B's state: TTL=4, Hop=1, Route=[NODE-A, NODE-B]
        assertEquals(4, parsedPacketAtC!!.ttl)
        assertEquals(1, parsedPacketAtC.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B"), parsedPacketAtC.routeHistory)

        // -------------------------------------------------------------
        // Step 7: Node C RoutingEngine processes incoming packet (Link B -> C)
        // -------------------------------------------------------------
        val neighborsOfC = listOf(
            Node(nodeId = "NODE-B", nodeName = "Node B", status = NodeStatus.CONNECTED)
        )
        val decisionAtC = engineC.processIncomingPacket(
            packet = parsedPacketAtC,
            currentNodeId = "NODE-C",
            connectedNeighbors = neighborsOfC
        )

        // -------------------------------------------------------------
        // Step 8: Node C delivers packet with final state
        // -------------------------------------------------------------
        assertTrue("Node C decision must be Deliver", decisionAtC is ForwardingDecision.Deliver)
        val deliveredPacketAtC = (decisionAtC as ForwardingDecision.Deliver).packet

        // Verify exact final delivered metrics at Node C
        assertEquals("MSG-B43572", deliveredPacketAtC.messageId)
        assertEquals("NODE-A", deliveredPacketAtC.sourceId)
        assertEquals("NODE-C", deliveredPacketAtC.destinationId)
        assertEquals(payloadText, deliveredPacketAtC.payload)
        assertEquals(3, deliveredPacketAtC.ttl)
        assertEquals(2, deliveredPacketAtC.hopCount)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C"), deliveredPacketAtC.routeHistory)
        assertEquals("NODE-C", deliveredPacketAtC.previousHopId)
    }
}
