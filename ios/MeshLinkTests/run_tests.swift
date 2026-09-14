import Foundation

// ANSI colors
let green = "\u{001B}[32m"
let red = "\u{001B}[31m"
let cyan = "\u{001B}[36m"
let yellow = "\u{001B}[33m"
let reset = "\u{001B}[0m"
let bold = "\u{001B}[1m"

print("\(bold)\(cyan)======================================================================\(reset)")
print("\(bold)\(cyan)MESHLINK IOS TEST SUITE & CROSS-PLATFORM PROTOCOL VERIFICATION\(reset)")
print("\(bold)\(cyan)======================================================================\(reset)\n")

var passedCount = 0
var failedCount = 0

func assertTest(_ condition: Bool, _ testName: String, failureDetails: String = "") {
    if condition {
        passedCount += 1
        print("  \(green)✓ [PASS]\(reset) \(testName)")
    } else {
        failedCount += 1
        print("  \(red)✗ [FAIL]\(reset) \(testName)")
        if !failureDetails.isEmpty {
            print("         \(yellow)Details: \(failureDetails)\(reset)")
        }
    }
}

// -----------------------------------------------------------------------------
// SUITE 1: MessagePacket & Serialization
// -----------------------------------------------------------------------------
print("\(bold)Suite 1: MessagePacket Header Mutation & Serialization\(reset)")

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
assertTest(forwardedByB.ttl == 4, "TTL decremented from 5 to 4")
assertTest(forwardedByB.hopCount == 1, "Hop count incremented from 0 to 1")
assertTest(forwardedByB.routeHistory == ["NODE-A", "NODE-B"], "Route history appended Node B: [NODE-A, NODE-B]")
assertTest(forwardedByB.previousHopId == "NODE-B", "Previous hop updated to NODE-B")

guard let jsonStr = PacketSerializer.packetToJson(original) else {
    assertTest(false, "JSON serialization failed")
    exit(1)
}
assertTest(!jsonStr.isEmpty, "JSON packet serialization produced non-empty string")

guard let deserialized = PacketSerializer.jsonToPacket(jsonStr) else {
    assertTest(false, "JSON deserialization failed")
    exit(1)
}
assertTest(deserialized.messageId == original.messageId, "Deserialized messageId matches")
assertTest(deserialized.sourceId == original.sourceId, "Deserialized sourceId matches")
assertTest(deserialized.destinationId == original.destinationId, "Deserialized destinationId matches")
assertTest(deserialized.payload == original.payload, "Deserialized payload matches")
assertTest(deserialized.ttl == original.ttl, "Deserialized TTL matches")
assertTest(deserialized.hopCount == original.hopCount, "Deserialized hopCount matches")
assertTest(deserialized.routeHistory == original.routeHistory, "Deserialized routeHistory matches")

// -----------------------------------------------------------------------------
// SUITE 2: Cross-Platform Wire Compatibility (Android <-> iOS)
// -----------------------------------------------------------------------------
print("\n\(bold)Suite 2: Cross-Platform Wire Compatibility (Android ↔ iOS)\(reset)")

let androidWireJson = """
{
    "messageId": "MSG-A1B2C3",
    "sourceId": "NODE-ANDROID-1",
    "sourceName": "Android Pixel",
    "destinationId": "NODE-IOS-1",
    "destinationName": "iPhone 15",
    "payload": "Hello from Android to iOS!",
    "ttl": 5,
    "hopCount": 0,
    "timestamp": 1700000000000,
    "routeHistory": ["NODE-ANDROID-1"],
    "previousHopId": "NODE-ANDROID-1"
}
"""

let decodedFromAndroid = PacketSerializer.jsonToPacket(androidWireJson)
assertTest(decodedFromAndroid != nil, "Android Kotlinx JSON decoded successfully by iOS Swift")
assertTest(decodedFromAndroid?.messageId == "MSG-A1B2C3", "Android messageId 'MSG-A1B2C3' preserved")
assertTest(decodedFromAndroid?.sourceId == "NODE-ANDROID-1", "Android sourceId 'NODE-ANDROID-1' preserved")
assertTest(decodedFromAndroid?.destinationId == "NODE-IOS-1", "Android destinationId 'NODE-IOS-1' preserved")
assertTest(decodedFromAndroid?.payload == "Hello from Android to iOS!", "Android payload preserved")
assertTest(decodedFromAndroid?.ttl == 5, "Android TTL 5 preserved")
assertTest(decodedFromAndroid?.routeHistory == ["NODE-ANDROID-1"], "Android routeHistory preserved")

let iosGeneratedPacket = MessagePacket(
    messageId: "MSG-IOS-999",
    sourceId: "NODE-IOS-1",
    sourceName: "iPhone 15",
    destinationId: "NODE-ANDROID-2",
    destinationName: "Android Galaxy",
    payload: "Cross-platform relay test",
    ttl: 4,
    hopCount: 1,
    timestamp: 1710000000000,
    routeHistory: ["NODE-ORIGIN", "NODE-IOS-1"],
    previousHopId: "NODE-IOS-1"
)

let iosWireJson = PacketSerializer.packetToJson(iosGeneratedPacket) ?? ""
assertTest(iosWireJson.contains("\"messageId\":\"MSG-IOS-999\""), "iOS wire JSON contains standard messageId key")
assertTest(iosWireJson.contains("\"sourceId\":\"NODE-IOS-1\""), "iOS wire JSON contains standard sourceId key")
assertTest(iosWireJson.contains("\"destinationId\":\"NODE-ANDROID-2\""), "iOS wire JSON contains standard destinationId key")
assertTest(iosWireJson.contains("\"ttl\":4"), "iOS wire JSON contains standard ttl numeric key")
assertTest(iosWireJson.contains("\"hopCount\":1"), "iOS wire JSON contains standard hopCount numeric key")

// -----------------------------------------------------------------------------
// SUITE 3: SeenMessageManager (Duplicate Cache & LRU)
// -----------------------------------------------------------------------------
print("\n\(bold)Suite 3: SeenMessageManager LRU Cache & Duplicate Prevention\(reset)")

let seenMgr = SeenMessageManager(maxCapacity: 3)
assertTest(!seenMgr.hasSeen("MSG-1"), "hasSeen returns false for unseen ID")
assertTest(seenMgr.markSeen("MSG-1"), "markSeen returns true for first registration")
assertTest(seenMgr.hasSeen("MSG-1"), "hasSeen returns true after marking")
assertTest(!seenMgr.markSeen("MSG-1"), "markSeen returns false for duplicate registration")

seenMgr.markSeen("MSG-2")
seenMgr.markSeen("MSG-3")
assertTest(seenMgr.size() == 3, "Seen cache reaches max capacity 3")

// Add 4th -> oldest (MSG-1) should be evicted
seenMgr.markSeen("MSG-4")
assertTest(seenMgr.size() == 3, "Seen cache size capped at max capacity")
assertTest(!seenMgr.hasSeen("MSG-1"), "Oldest entry MSG-1 evicted by LRU policy")
assertTest(seenMgr.hasSeen("MSG-2"), "MSG-2 retained")
assertTest(seenMgr.hasSeen("MSG-4"), "New MSG-4 present")

// -----------------------------------------------------------------------------
// SUITE 4: RoutingEngine Core Verification
// -----------------------------------------------------------------------------
print("\n\(bold)Suite 4: RoutingEngine Multi-Hop Forwarding & Loop Avoidance\(reset)")

let engine = RoutingEngine(seenMessageManager: SeenMessageManager())

// Test 1: Destination reached -> DELIVER
let destPacket = MessagePacket(
    messageId: "MSG-001",
    sourceId: "NODE-A",
    destinationId: "NODE-C",
    payload: "Hello Destination Node C",
    ttl: 5,
    hopCount: 1,
    routeHistory: ["NODE-A", "NODE-B"],
    previousHopId: "NODE-B"
)
let decision1 = engine.processIncomingPacket(
    packet: destPacket,
    currentNodeId: "NODE-C",
    connectedNeighbors: [Node(nodeId: "NODE-B", nodeName: "Node B", status: .connected)]
)
guard case .deliver(let delivered, _) = decision1 else {
    assertTest(false, "Test 1: Destination check failed")
    exit(1)
}
assertTest(delivered.destinationId == "NODE-C", "Test 1: Destination reached delivers payload to local node")

// Test 2: Relay Node -> FORWARD
let relayPacket = MessagePacket(
    messageId: "MSG-002",
    sourceId: "NODE-A",
    destinationId: "NODE-C",
    payload: "Multi-hop payload",
    ttl: 5,
    hopCount: 0,
    routeHistory: ["NODE-A"],
    previousHopId: "NODE-A"
)
let decision2 = engine.processIncomingPacket(
    packet: relayPacket,
    currentNodeId: "NODE-B",
    connectedNeighbors: [
        Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected),
        Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)
    ]
)
guard case .forward(let forwarded, let candidates, _) = decision2 else {
    assertTest(false, "Test 2: Relay forwarding failed")
    exit(1)
}
assertTest(forwarded.ttl == 4, "Test 2: Relay decrements TTL (5 -> 4)")
assertTest(forwarded.hopCount == 1, "Test 2: Relay increments hopCount (0 -> 1)")
assertTest(forwarded.routeHistory == ["NODE-A", "NODE-B"], "Test 2: Relay appends NODE-B to route history")
assertTest(candidates == ["NODE-C"], "Test 2: Loop avoidance excludes Node A (source/prev hop) and selects Node C")

// Test 3: Duplicate detection -> DROP_DUPLICATE
let decision3 = engine.processIncomingPacket(
    packet: relayPacket,
    currentNodeId: "NODE-B",
    connectedNeighbors: [Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)]
)
guard case .dropDuplicate = decision3 else {
    assertTest(false, "Test 3: Duplicate packet check failed")
    exit(1)
}
assertTest(true, "Test 3: Duplicate packet dropped to prevent broadcast storms")

// Test 4: TTL expired -> DROP_TTL_EXPIRED
let expiredPacket = MessagePacket(
    messageId: "MSG-004",
    sourceId: "NODE-A",
    destinationId: "NODE-C",
    payload: "Expired payload",
    ttl: 1,
    hopCount: 4,
    routeHistory: ["NODE-A", "NODE-X"],
    previousHopId: "NODE-X"
)
let decision4 = engine.processIncomingPacket(
    packet: expiredPacket,
    currentNodeId: "NODE-B",
    connectedNeighbors: [Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)]
)
guard case .dropTtlExpired = decision4 else {
    assertTest(false, "Test 4: TTL expiration check failed")
    exit(1)
}
assertTest(true, "Test 4: Expired packet (TTL <= 1) dropped to prevent eternal circulation")

// Test 5: End-to-End Three-Node Simulation (A -> B -> C)
print("\n\(bold)Suite 5: End-to-End Three-Node Flow (Node A ➔ Node B [Relay] ➔ Node C)\(reset)")

let engineA = RoutingEngine()
let engineB = RoutingEngine()
let engineC = RoutingEngine()

let packetA = engineA.createLocalPacket(
    messageId: "MSG-E2E-100",
    sourceId: "NODE-A",
    sourceName: "Node A",
    destinationId: "NODE-C",
    destinationName: "Node C",
    payload: "End-to-End Multi-Hop Mesh Payload",
    initialTtl: 5
)
assertTest(packetA.sourceId == "NODE-A" && packetA.ttl == 5 && packetA.hopCount == 0, "Node A initialized packet correctly")

let decisionB_sim = engineB.processIncomingPacket(
    packet: packetA,
    currentNodeId: "NODE-B",
    connectedNeighbors: [
        Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected),
        Node(nodeId: "NODE-C", nodeName: "Node C", status: .connected)
    ]
)
guard case .forward(let forwardedFromB, let candidatesFromB, _) = decisionB_sim else {
    assertTest(false, "Node B failed to relay packet")
    exit(1)
}
assertTest(forwardedFromB.ttl == 4 && forwardedFromB.hopCount == 1, "Node B relayed: TTL 5->4, Hops 0->1")
assertTest(forwardedFromB.routeHistory == ["NODE-A", "NODE-B"], "Node B recorded path: NODE-A -> NODE-B")
assertTest(candidatesFromB == ["NODE-C"], "Node B forwarded packet to Node C")

let decisionC_sim = engineC.processIncomingPacket(
    packet: forwardedFromB,
    currentNodeId: "NODE-C",
    connectedNeighbors: [Node(nodeId: "NODE-B", nodeName: "Node B", status: .connected)]
)
guard case .deliver(let deliveredAtC, _) = decisionC_sim else {
    assertTest(false, "Node C failed to deliver packet")
    exit(1)
}
assertTest(deliveredAtC.destinationId == "NODE-C", "Node C identified itself as ultimate destination")
assertTest(deliveredAtC.payload == "End-to-End Multi-Hop Mesh Payload", "Node C received intact payload")
assertTest(deliveredAtC.routeHistory == ["NODE-A", "NODE-B"], "Complete traversed route verified as NODE-A -> NODE-B")

// Test 6: Loop Avoidance & Candidate Selection
let loopPacket = MessagePacket(
    messageId: "MSG-006",
    sourceId: "NODE-A",
    destinationId: "NODE-D",
    payload: "Loop test",
    ttl: 5,
    hopCount: 2,
    routeHistory: ["NODE-A", "NODE-X"],
    previousHopId: "NODE-X"
)
let decision6 = engine.processIncomingPacket(
    packet: loopPacket,
    currentNodeId: "NODE-B",
    connectedNeighbors: [
        Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected),
        Node(nodeId: "NODE-X", nodeName: "Node X", status: .connected),
        Node(nodeId: "NODE-Y", nodeName: "Node Y", status: .connected)
    ]
)
guard case .forward(_, let candidates6, _) = decision6 else {
    assertTest(false, "Loop avoidance test failed")
    exit(1)
}
assertTest(candidates6 == ["NODE-Y"], "Loop avoidance successfully excluded NODE-A (routeHistory) and NODE-X (previousHop)")

// Test 7: No Route Available -> DROP_NO_ROUTE
let deadEndPacket = MessagePacket(
    messageId: "MSG-007",
    sourceId: "NODE-A",
    destinationId: "NODE-C",
    payload: "Dead end test",
    ttl: 5,
    hopCount: 1,
    routeHistory: ["NODE-A"],
    previousHopId: "NODE-A"
)
let decision7 = engine.processIncomingPacket(
    packet: deadEndPacket,
    currentNodeId: "NODE-B",
    connectedNeighbors: [Node(nodeId: "NODE-A", nodeName: "Node A", status: .connected)]
)
guard case .dropNoRoute = decision7 else {
    assertTest(false, "No route check failed")
    exit(1)
}
assertTest(true, "No-route packet dropped when all connected neighbors are in excluded set")

print("\n\(bold)\(cyan)======================================================================\(reset)")
print("\(bold)\(green)TOTAL TESTS PASSED: \(passedCount)\(reset)")
if failedCount == 0 {
    print("\(bold)\(green)ALL TESTS PASSED WITH 100% SUCCESS! WIRE & LOGIC COMPATIBILITY VERIFIED.\(reset)")
} else {
    print("\(bold)\(red)TESTS FAILED: \(failedCount)\(reset)")
}
print("\(bold)\(cyan)======================================================================\(reset)\n")
