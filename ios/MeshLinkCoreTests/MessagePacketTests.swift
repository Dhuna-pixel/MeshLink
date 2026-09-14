import XCTest
@testable import MeshLinkCore

final class MessagePacketTests: XCTestCase {

    func testPacketForwardingMutation() {
        let original = MessagePacket(
            messageId: "MSG-100",
            sourceId: "NODE-A",
            sourceName: "Node A",
            destinationId: "NODE-C",
            destinationName: "Node C",
            payload: "Test Multi-Hop Forwarding",
            ttl: 5,
            hopCount: 0,
            timestamp: 1700000000000,
            routeHistory: ["NODE-A"],
            previousHopId: "NODE-A"
        )

        let forwardedByB = original.createForwardedPacket(relayNodeId: "NODE-B")

        XCTAssertEqual(forwardedByB.ttl, 4)
        XCTAssertEqual(forwardedByB.hopCount, 1)
        XCTAssertEqual(forwardedByB.routeHistory, ["NODE-A", "NODE-B"])
        XCTAssertEqual(forwardedByB.previousHopId, "NODE-B")
        XCTAssertEqual(forwardedByB.messageId, "MSG-100")
        XCTAssertEqual(forwardedByB.sourceId, "NODE-A")
        XCTAssertEqual(forwardedByB.destinationId, "NODE-C")
    }

    func testJsonSerializationRoundtrip() {
        let original = MessagePacket(
            messageId: "MSG-JSON-99",
            sourceId: "NODE-A",
            sourceName: "Alpha",
            destinationId: "NODE-C",
            destinationName: "Charlie",
            payload: "Structured Mesh Payload",
            ttl: 3,
            hopCount: 2,
            timestamp: 1700000000000,
            routeHistory: ["NODE-A", "NODE-B"],
            previousHopId: "NODE-B"
        )

        guard let jsonStr = PacketSerializer.packetToJson(original) else {
            XCTFail("Failed to serialize packet to JSON")
            return
        }

        XCTAssertFalse(jsonStr.isEmpty)

        guard let deserialized = PacketSerializer.jsonToPacket(jsonStr) else {
            XCTFail("Failed to deserialize packet from JSON")
            return
        }

        XCTAssertEqual(deserialized.messageId, original.messageId)
        XCTAssertEqual(deserialized.sourceId, original.sourceId)
        XCTAssertEqual(deserialized.sourceName, original.sourceName)
        XCTAssertEqual(deserialized.destinationId, original.destinationId)
        XCTAssertEqual(deserialized.destinationName, original.destinationName)
        XCTAssertEqual(deserialized.payload, original.payload)
        XCTAssertEqual(deserialized.ttl, original.ttl)
        XCTAssertEqual(deserialized.hopCount, original.hopCount)
        XCTAssertEqual(deserialized.timestamp, original.timestamp)
        XCTAssertEqual(deserialized.routeHistory, original.routeHistory)
        XCTAssertEqual(deserialized.previousHopId, original.previousHopId)
    }
}
