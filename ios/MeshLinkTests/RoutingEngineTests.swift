import XCTest
@testable import MeshLink

final class RoutingEngineTests: XCTestCase {

    private var routingEngine: RoutingEngine!

    override func setUp() {
        super.setUp()
        routingEngine = RoutingEngine(seenMessageManager: SeenMessageManager())
    }

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
        XCTAssertEqual(candidateNextHopNodeIds, ["NODE-C"])
    }

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

        let firstDecision = routingEngine.processIncomingPacket(
            packet: packet,
            currentNodeId: "NODE-B",
            connectedNeighbors: neighbors
        )
        guard case .forward = firstDecision else {
            XCTFail("First decision should be Forward")
            return
        }

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

    func test4_expiredTtl_dropped() {
        let packetWithTtl1 = MessagePacket(
            messageId: "MSG-004",
            sourceId: "NODE-A",
            destinationId: "NODE-C",
            payload: "Expired test payload",
            ttl: 1,
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

    func test5_threeNodeMultiHopFlow_success() {
        let engineA = RoutingEngine()
        let engineB = RoutingEngine()
        let engineC = RoutingEngine()

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
