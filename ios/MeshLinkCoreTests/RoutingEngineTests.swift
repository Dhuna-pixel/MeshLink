import XCTest
@testable import MeshLinkCore

final class RoutingEngineTests: XCTestCase {

    private var routingEngine: RoutingEngine!

    override func setUp() {
        super.setUp()
        routingEngine = RoutingEngine(seenMessageManager: SeenMessageManager())
    }

    /**
     * Test 1 — Destination reached
     * When a packet arrives and packet.destinationId == currentNodeId,
     * the engine MUST return ForwardingDecision.deliver.
     */
    func test1_destinationReached_deliversPayload() {
        let packet = MessagePacket(
            messageId: "MSG-001",
            sourceId: "NODE-A",
            destinationId: "NODE-C",
            payload: "Hello Destination Node C",
            ttl: 5,
            hopCount: 1,
            routeHistory: ["NODE-A", "NODE-B"],
            previousHopId: "NODE-B"
        )

        let neighborsOfC = [
            Node(nodeId: "NODE-B", nodeName: "Node B", status: .connected)
        ]

        let decision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-C",
            connectedNeighbors: neighborsOfC
        )

        guard case .deliver(let deliveredPacket, _) = decision else {
            XCTFail("Decision should be Deliver, got: \(decision)")
            return
        }

        XCTAssertEqual(deliveredPacket.destinationId, "NODE-C")
        XCTAssertEqual(deliveredPacket.payload, "Hello Destination Node C")
    }

    /**
     * Test 2 — Forward
     * When destination != currentNodeId and TTL > 1,
     * the engine MUST return ForwardingDecision.forward with:
     * - TTL decremented (e.g. 5 -> 4)
     * - hopCount incremented (e.g. 0 -> 1)
     * - current node appended to routeHistory
     */
    func test2_relayNode_forwardsPacket_andUpdatesTtlAndHops() {
        let packet = MessagePacket(
            messageId: "MSG-002",
            sourceId: "NODE-A",
            destinationId: "NODE-C",
            payload: "Multi-hop payload",
            ttl: 5,
            hopCount: 0,
            routeHistory: ["NODE-A"],
            previousHopId: "NODE-A"
        )

        let neighborsOfB = [
            Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected),
            Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)
        ]

        let decision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighborsOfB
        )

        guard case .forward(let updatedPacket, let candidateNextHopNodeIds, _) = decision else {
            XCTFail("Decision should be Forward, got: \(decision)")
            return
        }

        XCTAssertEqual(updatedPacket.ttl, 4)
        XCTAssertEqual(updatedPacket.hopCount, 1)
        XCTAssertEqual(updatedPacket.routeHistory, ["NODE-A", "NODE-B"])
        XCTAssertEqual(updatedPacket.previousHopId, "NODE-B")

        // Node A (source & previous hop) excluded, Node C selected as target
        XCTAssertEqual(candidateNextHopNodeIds, ["NODE-C"])
    }

    /**
     * Test 3 — Duplicate detection
     * If the same messageId is received a second time,
     * the engine MUST return ForwardingDecision.dropDuplicate.
     */
    func test3_duplicatePacket_dropped() {
        let packet = MessagePacket(
            messageId: "MSG-003",
            sourceId: "NODE-A",
            destinationId: "NODE-C",
            payload: "Duplicate test payload",
            ttl: 5,
            hopCount: 0,
            routeHistory: ["NODE-A"],
            previousHopId: "NODE-A"
        )

        let neighbors = [
            Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)
        ]

        // First processing -> Should forward
        let firstDecision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighbors
        )
        guard case .forward = firstDecision else {
            XCTFail("First decision should be Forward")
            return
        }

        // Second processing with identical messageId -> Must be dropped as duplicate
        let secondDecision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighbors
        )
        guard case .dropDuplicate = secondDecision else {
            XCTFail("Second receipt must be dropped as duplicate, got: \(secondDecision)")
            return
        }
    }

    /**
     * Test 4 — TTL expired
     * When TTL <= 1, forwarding would exceed hop limit,
     * the engine MUST return ForwardingDecision.dropTtlExpired.
     */
    func test4_expiredTtl_dropped() {
        let packetWithTtl1 = MessagePacket(
            messageId: "MSG-004",
            sourceId: "NODE-A",
            destinationId: "NODE-C",
            payload: "Expired test payload",
            ttl: 1, // TTL = 1 cannot be forwarded further
            hopCount: 4,
            routeHistory: ["NODE-A", "NODE-X", "NODE-Y", "NODE-Z"],
            previousHopId: "NODE-Z"
        )

        let neighbors = [
            Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)
        ]

        let decision = routingEngine.processIncomingPacket(
            packet: packetWithTtl1,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighbors
        )

        guard case .dropTtlExpired = decision else {
            XCTFail("Decision should be DropTtlExpired, got: \(decision)")
            return
        }
    }

    /**
     * Test 5 — Complete Three-Node multi-hop flow (A -> B -> C)
     * Simulates end-to-end:
     * 1. Node A creates packet for Node C
     * 2. Node B (Relay) processes and forwards to Node C
     * 3. Node C (Destination) receives and delivers
     */
    func test5_threeNodeMultiHopFlow_success() {
        let engineA = RoutingEngine()
        let engineB = RoutingEngine()
        let engineC = RoutingEngine()

        // 1. Node A creates local packet
        let packetFromA = engineA.createLocalPacket(
            messageId: "MSG-FLOW-100",
            sourceId: "NODE-A",
            sourceName: "Node A",
            destinationId: "NODE-C",
            destinationName: "Node C",
            payload: "End-to-End MeshLink Test",
            initialTtl: 5
        )

        XCTAssertEqual(packetFromA.sourceId, "NODE-A")
        XCTAssertEqual(packetFromA.destinationId, "NODE-C")
        XCTAssertEqual(packetFromA.ttl, 5)
        XCTAssertEqual(packetFromA.hopCount, 0)
        XCTAssertEqual(packetFromA.routeHistory, ["NODE-A"])

        // 2. Node B (Relay) receives packet from Node A
        let neighborsOfB = [
            Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected),
            Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)
        ]

        let decisionAtB = engineB.processIncomingPacket(
            packet: packetFromA,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighborsOfB
        )

        guard case .forward(let forwardedPacketByB, _, _) = decisionAtB else {
            XCTFail("Node B must forward the packet")
            return
        }

        XCTAssertEqual(forwardedPacketByB.ttl, 4)
        XCTAssertEqual(forwardedPacketByB.hopCount, 1)
        XCTAssertEqual(forwardedPacketByB.routeHistory, ["NODE-A", "NODE-B"])

        // 3. Node C (Destination) receives forwarded packet from Node B
        let neighborsOfC = [
            Node(nodeId: "NODE-B", nodeName: "Node B", status: .connected)
        ]

        let decisionAtC = engineC.processIncomingPacket(
            packet: forwardedPacketByB,
            currentNodeId: "NODE-C",
            connectedNeighbors: neighborsOfC
        )

        guard case .deliver(let deliveredPacket, _) = decisionAtC else {
            XCTFail("Node C must deliver the packet")
            return
        }

        XCTAssertEqual(deliveredPacket.sourceId, "NODE-A")
        XCTAssertEqual(deliveredPacket.destinationId, "NODE-C")
        XCTAssertEqual(deliveredPacket.payload, "End-to-End MeshLink Test")
        XCTAssertEqual(deliveredPacket.hopCount, 1)
        XCTAssertEqual(deliveredPacket.ttl, 4)
        XCTAssertEqual(deliveredPacket.routeHistory, ["NODE-A", "NODE-B"])
    }

    /**
     * Test 6 — Loop Avoidance
     * Verifies that neighbors already in route history are excluded from candidate next hops.
     */
    func test6_loopAvoidance_excludesRouteHistoryAndPreviousHop() {
        let packet = MessagePacket(
            messageId: "MSG-006",
            sourceId: "NODE-A",
            destinationId: "NODE-D",
            payload: "Loop test",
            ttl: 5,
            hopCount: 2,
            routeHistory: ["NODE-A", "NODE-X"],
            previousHopId: "NODE-X"
        )

        // Node B has connected neighbors: Node A (in history), Node X (previous hop), Node Y (new valid neighbor)
        let neighborsOfB = [
            Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected),
            Node(nodeId: "NODE-X", nodeName: "Node X", status: .connected),
            Node(nodeId: "NODE-Y", nodeName: "Node Y", status: .connected)
        ]

        let decision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighborsOfB
        )

        guard case .forward(_, let candidates, _) = decision else {
            XCTFail("Decision should be Forward")
            return
        }

        XCTAssertEqual(candidates, ["NODE-Y"])
    }

    /**
     * Test 7 — No route available
     * When no valid unvisited connected neighbors exist, engine returns DropNoRoute.
     */
    func test7_noRouteAvailable_returnsDropNoRoute() {
        let packet = MessagePacket(
            messageId: "MSG-007",
            sourceId: "NODE-A",
            destinationId: "NODE-C",
            payload: "Dead end test",
            ttl: 5,
            hopCount: 1,
            routeHistory: ["NODE-A"],
            previousHopId: "NODE-A"
        )

        // Only neighbor is Node A (previous hop)
        let neighborsOfB = [
            Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected)
        ]

        let decision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighborsOfB
        )

        guard case .dropNoRoute = decision else {
            XCTFail("Should drop when no next hop is available, got: \(decision)")
            return
        }
    }
}
