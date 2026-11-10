> Historical record: the obsolete Apple-platform source and build projects were removed during the Android-only cleanup. Commands and source paths below describe the earlier implementation.

# MeshLink iOS Architecture Specification

This document details the architectural design, domain models, routing engine mechanics, transport layer decoupling, and thread-safety guarantees of the **MeshLink iOS** implementation.

---

## 1. Architectural Philosophy

MeshLink iOS follows **Clean Architecture** principles and **MVVM** (Model-View-ViewModel) pattern to guarantee:
1. **Domain Isolation**: Pure domain logic (`RoutingEngine`, `SeenMessageManager`, `MessagePacket`, `ForwardingDecision`) has **zero dependencies** on Apple frameworks (`CoreBluetooth`, `MultipeerConnectivity`, `UIKit`, `SwiftUI`).
2. **Platform Equivalence**: The 5-step routing algorithm and serialization wire format match the Android Kotlin implementation verbatim.
3. **Transport Decoupling**: Networking protocols conform to the `MeshTransport` interface, enabling interchangeable operation between physical Bluetooth Low Energy (BLE), Apple Multipeer Connectivity, and in-memory multi-node simulations.
4. **Deterministic Forwarding**: Decisions are expressed as a Swift enum (`ForwardingDecision`) mirroring Android's sealed class hierarchy.

---

## 2. Layered Architecture

```
┌────────────────────────────────────────────────────────┐
│                   PRESENTATION LAYER                   │
│   • SwiftUI Screens (Status, Neighbors, Messaging,     │
│                      Logs, Simulation)                 │
│   • MeshViewModel (ObservableObject state holder)      │
│   • MeshUiState (Single Source of Truth)               │
└───────────────────────────┬────────────────────────────┘
                            │ (Observes & Dispatches)
┌───────────────────────────▼────────────────────────────┐
│                    REPOSITORY LAYER                    │
│   • MeshRepository                                     │
│     - Coordinates active transport & routing engine    │
│     - Emits reactive State / Published streams         │
│     - Maintains local node identity & event logs       │
└──────────────────┬──────────────────┬──────────────────┘
                   │                  │
┌──────────────────▼──────────┐ ┌─────▼──────────────────┐
│        DOMAIN LAYER         │ │    TRANSPORT LAYER     │
│ • RoutingEngine             │ │ • MeshTransport (Proto)│
│ • SeenMessageManager (LRU)  │ │ • CoreBluetoothTransport│
│ • MessagePacket             │ │ • MultipeerTransport   │
│ • ForwardingDecision        │ │ • SimulationTransport  │
│ • Node & NetworkLogEntry    │ └────────────────────────┘
│ • PacketSerializer          │
└─────────────────────────────┘
```

---

## 3. Domain Model Specifications

### 3.1 `MessagePacket`
The foundational wire packet exchanged between MeshLink nodes:

```swift
public struct MessagePacket: Codable, Identifiable, Equatable, Sendable {
    public var id: String { messageId }

    public let messageId: String          // e.g. "MSG-A1B2C3"
    public let sourceId: String           // e.g. "NODE-A"
    public let sourceName: String         // e.g. "Node A (iOS)"
    public let destinationId: String      // e.g. "NODE-C"
    public let destinationName: String    // e.g. "Node C"
    public let payload: String            // Content payload
    public let ttl: Int                   // Time-To-Live (hop limit)
    public let hopCount: Int              // Number of hops traversed
    public let timestamp: Int64           // Milliseconds since epoch
    public let routeHistory: [String]     // Nodes that routed this packet
    public let previousHopId: String?     // Immediate preceding node ID
}
```

#### Forwarding Packet Mutation (`createForwardedPacket`)
When an intermediate relay node `NODE-B` forwards a packet:
1. `ttl` is decremented by 1: `ttl = ttl - 1`
2. `hopCount` is incremented by 1: `hopCount = hopCount + 1`
3. `routeHistory` is appended with the relay's ID: `routeHistory + [relayNodeId]` (if not already recorded)
4. `previousHopId` is updated to the relay's ID: `previousHopId = relayNodeId`

---

## 4. Routing Engine Mechanics

The `RoutingEngine` processes every incoming or locally generated packet through 5 deterministic evaluation stages:

```
                  ┌──────────────────────┐
                  │ Incoming Message     │
                  │ Packet               │
                  └──────────┬───────────┘
                             │
                  ┌──────────▼───────────┐
                  │ 1. Duplicate Check   │──(Already Seen)──► DropDuplicate
                  └──────────┬───────────┘
                             │ (New Message)
                  ┌──────────▼───────────┐
                  │ 2. Destination Match │──(Is Local Node)─► Deliver
                  └──────────┬───────────┘
                             │ (Not Destination)
                  ┌──────────▼───────────┐
                  │ 3. TTL Validation    │──(TTL <= 1)──────► DropTtlExpired
                  └──────────┬───────────┘
                             │ (TTL > 1)
                  ┌──────────▼───────────┐
                  │ 4. Next-Hop Selection│──(No Candidate)──► DropNoRoute
                  │    & Loop Avoidance  │
                  └──────────┬───────────┘
                             │ (Candidates Found)
                  ┌──────────▼───────────┐
                  │ 5. Packet Mutation   │──────────────────► Forward
                  │    & Relay Dispatch  │
                  └──────────────────────┘
```

### Stage Details

1. **Duplicate Check (`SeenMessageManager`)**:
   - Checks if `packet.messageId` exists in the LRU seen cache.
   - If present: returns `ForwardingDecision.dropDuplicate`.
   - If not present: marks `packet.messageId` as seen immediately.

2. **Destination Check**:
   - Performs a case-insensitive check: `packet.destinationId == currentNodeId`.
   - If matched: returns `ForwardingDecision.deliver(packet:reason:)`.

3. **TTL Check**:
   - Evaluates `packet.ttl <= 1`.
   - If true: forwarding would result in `TTL <= 0`, so the packet is discarded with `ForwardingDecision.dropTtlExpired`.

4. **Next-Hop Selection & Loop Prevention**:
   - Retrieves all active neighbors with `status == .connected`.
   - Builds an `excludedNodeIds` set containing:
     - The current node ID
     - The `previousHopId` that transmitted the packet (avoids immediate echo)
     - All node IDs present in `packet.routeHistory` (avoids multi-hop cyclic loops)
   - Filters candidate neighbors: `candidateNeighbors = activeNeighbors.filter { !excludedNodeIds.contains($0.nodeId) }`.
   - If empty: returns `ForwardingDecision.dropNoRoute`.
   - If the ultimate destination is directly in candidates, selects `[directNeighbor.nodeId]`; otherwise targets all candidate neighbors.

5. **Packet Transformation & Forwarding**:
   - Invokes `packet.createForwardedPacket(relayNodeId: currentNodeId)`.
   - Returns `ForwardingDecision.forward(updatedPacket:candidateNextHopNodeIds:reason:)`.

---

## 5. SeenMessageManager (Thread-Safe LRU Cache)

To protect memory and prevent indefinite state growth on resource-constrained mobile hardware:
- Backed by an internal `Set<String>` and chronological `[String]` tracking array.
- Protected by an `NSLock` for concurrent access across multiple transport background threads.
- Defaults to a capacity of `500` message IDs.
- Automatically evicts the oldest recorded message ID when capacity is exceeded.

---

## 6. Transport Abstraction

The `MeshTransport` protocol standardizes peer discovery, connection establishment, packet transmission, and event dispatching:

```swift
public protocol MeshTransport: AnyObject, Sendable {
    var isRunning: Bool { get }
    var onEvent: (@Sendable (TransportEvent) -> Void)? { get set }

    func start(localNode: Node)
    func stop()
    func connectToNode(_ targetNode: Node)
    func disconnectNode(nodeId: String)
    func sendPacket(_ packet: MessagePacket, targetEndpointId: String) -> Bool
    func broadcastPacket(_ packet: MessagePacket, excludeEndpointIds: Set<String>) -> Int
}
```

### Supported Transports:

| Transport | Technology | Description |
|---|---|---|
| `CoreBluetoothTransport` | BLE GATT | Dual-role Peripheral/Central running over Bluetooth Low Energy. Cross-platform with Android. |
| `MultipeerMeshTransport` | Multipeer Connectivity | Apple-native peer transport for high-bandwidth iOS-to-iOS communication. |
| `SimulationTransport` | Virtual In-Memory | 3-Node virtual topology running independent `RoutingEngine` instances for local testing. |
