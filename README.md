# MeshLink (Version 0.1 — Review II Prototype)

> **Infrastructure-Free Decentralized Multi-Hop Mesh Network for Android**  
> *Undergraduate Engineering Capstone / Technical Review II Milestone*

---

## 1. Project Overview

Modern mobile communications critically depend on centralized cellular towers, Wi-Fi access points, and internet infrastructure. In disaster zones, remote regions, deep underground structures, or during catastrophic network blackouts, this centralized infrastructure fails completely.

**MeshLink** is an infrastructure-free peer-to-peer ad-hoc communication network for Android. Rather than routing messages through centralized servers or internet routers, participating Android devices form an autonomous multi-hop mesh.

### The Central Engineering Concept

```
[ Node A ]  ────────►  [ Node B ]  ────────►  [ Node C ]
 (Source)                (Relay)              (Destination)
```

The primary engineering breakthrough demonstrated in **Review II** is **genuine application-level multi-hop forwarding**:
* A message created by **Node A** intended for **Node C** is transmitted over the air to **Node B**.
* **Node B** receives the packet, evaluates its routing rules, confirms it is *not* the destination, validates the Time-To-Live (TTL), updates packet metrics (decrements TTL, increments hop count, appends itself to the route history), and retransmits the packet to **Node C**.
* **Node C** receives the packet, recognizes itself as the destination, and delivers the payload to the user.

---

## 2. Review II Scope & Verification Checklist

This prototype represents the **Review II (20% Milestone)** deliverables:

| Requirement | Implementation Component | Status |
| :--- | :--- | :---: |
| **Clean Modular Architecture** | UI (Compose) $\to$ Presentation (MVVM) $\to$ Domain (Pure Kotlin) $\to$ Data (Transport) | ✅ Complete |
| **Structured Message Packets** | `MessagePacket` with ID, Source, Dest, Payload, TTL, Hops, Route History | ✅ Complete |
| **Duplicate Prevention** | `SeenMessageManager` (Thread-safe LRU ring cache) | ✅ Complete |
| **Hop Limit & Loop Prevention** | Strict TTL validation (`DropTtlExpired`) & previous-hop / route-history filtering | ✅ Complete |
| **Genuine Relay Node (B)** | `RoutingEngine.processIncomingPacket()` mutates and forwards packet | ✅ Complete |
| **Peer Discovery & Connectivity** | `NearbyConnectionsTransport` using Google Nearby `P2P_CLUSTER` | ✅ Complete |
| **Real-Time Engineering Logging** | `LogScreen` with timestamps, log levels, and packet metadata | ✅ Complete |
| **In-Process 3-Node Simulation** | `SimulationTransport` running 3 concurrent `RoutingEngine` instances for instant demo | ✅ Complete |
| **Comprehensive Unit Testing** | `RoutingEngineTest` covering all 5 core routing cases | ✅ Complete |

---

## 3. Technology Stack & Transport Selection

### Why Google Nearby Connections API (`Strategy.P2P_CLUSTER`)?

For Android peer-to-peer ad-hoc mesh communication, three primary transport candidates exist:

1. **Wi-Fi Direct (Wi-Fi P2P)**: Requires a strict Group Owner (GO) star topology. Does not easily support arbitrary multi-hop chaining where Node B is concurrently connected as a client to Node A and a GO to Node C.
2. **Raw Bluetooth RFCOMM Sockets**: Requires complex manual thread management, UUID negotiation, pairing popups, and is prone to connection drops when multiple sockets are maintained simultaneously.
3. **Google Play Services Nearby Connections (`P2P_CLUSTER`)**: 
   * **Selected Solution**: `P2P_CLUSTER` is designed specifically for **M-to-N ad-hoc mesh clustering**.
   * It handles Bluetooth LE advertising/discovery, Bluetooth Classic pairing, and Wi-Fi Direct socket negotiation dynamically under the hood without requiring internet, cellular data, or local Wi-Fi routers.
   * Node B can advertise, discover, and maintain active connections with Node A and Node C concurrently.

### Software Stack

* **Platform**: Android (minSdk 24, targetSdk 34)
* **Language**: Kotlin 1.9.23
* **UI Toolkit**: Jetpack Compose (Material 3 Dark Engineering Theme)
* **Architecture**: MVVM + Clean Architecture + StateFlow
* **Serialization**: Kotlinx Serialization JSON / Gson
* **Testing**: JUnit 4 + Coroutines Test Framework

---

## 4. Architecture & Module Structure

```
MeshLink
│
├── app/src/main/java/com/meshlink/app/
│   │
│   ├── domain/                         <── 100% Pure Kotlin Domain Layer (Zero UI dependencies)
│   │   ├── models/
│   │   │   ├── Node.kt                 <── Node representation, status, and endpoint mapping
│   │   │   ├── MessagePacket.kt        <── Wire packet structure (TTL, Hops, RouteHistory)
│   │   │   ├── ForwardingDecision.kt   <── Sealed class: Deliver, Forward, DropDuplicate, DropTtlExpired, DropNoRoute
│   │   │   └── NetworkLogEntry.kt      <── Real-time engineering log events
│   │   │
│   │   └── routing/
│   │       ├── RoutingEngine.kt        <── Core multi-hop forwarding & loop avoidance logic
│   │       └── SeenMessageManager.kt   <── LRU cache preventing duplicate packet processing
│   │
│   ├── data/                           <── Data & Networking Layer
│   │   ├── transport/
│   │   │   ├── MeshTransport.kt        <── Abstract interface decoupling transport from routing
│   │   │   ├── NearbyConnectionsTransport.kt <── Physical device-to-device transport (P2P_CLUSTER)
│   │   │   └── SimulationTransport.kt  <── In-process 3-node virtual mesh for rapid verification
│   │   │
│   │   └── repository/
│   │       └── MeshRepository.kt       <── State coordination & routing dispatcher
│   │
│   ├── presentation/                   <── MVVM Presentation Layer
│   │   └── viewmodels/
│   │       ├── MeshUiState.kt          <── Immutable UI state model
│   │       └── MeshViewModel.kt        <── Handles user actions & state flows
│   │
│   ├── ui/                             <── Jetpack Compose UI
│   │   ├── components/
│   │   │   ├── StatusBadges.kt         <── Node and log level status pills
│   │   │   ├── PermissionHandler.kt    <── Android 12/13/14+ permission requester
│   │   │   ├── PacketInspectorDialog.kt <── Detailed packet header and wire JSON inspector
│   │   │   └── NodeIdentityDialog.kt   <── Configure Node ID/Name with A, B, C presets
│   │   │
│   │   ├── screens/
│   │   │   ├── HomeScreen.kt           <── Local node identity & network status
│   │   │   ├── NeighborsScreen.kt      <── Discovered & active connected peers
│   │   │   ├── MessagingScreen.kt      <── Message composer, TTL slider, and packet history
│   │   │   ├── LogScreen.kt            <── Terminal-style real-time event log
│   │   │   ├── SimulationScreen.kt     <── Interactive 3-node visualizer
│   │   │   └── MainScreen.kt           <── Top bar, bottom navigation & dialog host
│   │   │
│   │   └── theme/                      <── High-contrast Dark Engineering Theme
│   │
│   └── utils/
│       ├── JsonSerializer.kt           <── Wire serialization utility
│       └── IdGenerator.kt              <── Readable ID generator (e.g. MSG-001, NODE-A)
│
└── app/src/test/java/com/meshlink/app/  <── Unit Test Suite
    ├── domain/routing/RoutingEngineTest.kt <── Verifies 5 core routing requirements
    └── domain/models/MessagePacketTest.kt <── Verifies packet mutation & JSON roundtrip
```

---

## 5. Forwarding Logic (How Node B Acts as a Real Relay)

When any node receives a `MessagePacket` over the transport, `RoutingEngine.processIncomingPacket(...)` executes the following algorithmic steps:

```
                  ┌──────────────────────────────┐
                  │   Incoming MessagePacket     │
                  └──────────────┬───────────────┘
                                 │
                     [ Step 1: Duplicate Check ]
                     Has messageId been processed?
                                 │
                   YES ──────────┴────────── NO
                    │                         │
          ┌─────────────────┐       [ Step 2: Destination Check ]
          │  DROP_DUPLICATE │       Is packet.destinationId == currentNodeId?
          └─────────────────┘                 │
                                YES ──────────┴────────── NO
                                 │                         │
                       ┌──────────────────┐      [ Step 3: TTL Check ]
                       │ DELIVER TO USER  │      Is packet.ttl <= 1?
                       └──────────────────┘                │
                                             YES ──────────┴────────── NO
                                              │                         │
                                    ┌───────────────────┐    [ Step 4: Next Hop Selection ]
                                    │ DROP_TTL_EXPIRED  │    Filter connected neighbors
                                    └───────────────────┘    (Exclude previousHop & history)
                                                                        │
                                                             [ Step 5: Packet Mutation ]
                                                             • ttl = ttl - 1
                                                             • hopCount = hopCount + 1
                                                             • routeHistory += currentNodeId
                                                             • previousHopId = currentNodeId
                                                                        │
                                                             [ Step 6: Retransmission ]
                                                             FORWARD to candidate neighbor(s)
```

---

## 6. How to Build & Run

### Prerequisites

* **Android Studio**: Android Studio Hedgehog (2023.1.1) or newer (Ladybug / Jellyfish recommended).
* **JDK**: OpenJDK 17.
* **Target Devices**: Android 7.0 (API 24) or higher (Physical Android phones with Bluetooth and Wi-Fi enabled).

### Step-by-Step Build Instructions

1. Open Android Studio.
2. Select **File $\to$ Open...** and choose this `MeshLink` directory.
3. Allow Gradle to sync dependencies automatically.
4. Connect your Android device via USB or Wi-Fi debugging.
5. Click the green **Run 'app'** button (or press `Shift + F10`).

---

## 7. How to Test on Three Physical Android Phones

Follow these steps to demonstrate the full **$A \to B \to C$** multi-hop communication during the project review:

### Step 1: Setup Three Android Devices

1. Install and launch MeshLink on all three phones: **Phone 1**, **Phone 2**, and **Phone 3**.
2. Grant Bluetooth, Location, and Nearby Wi-Fi permissions when prompted.
3. Configure their identities using the **Settings (gear icon)** in the top app bar:
   * **Phone 1**: Select **Node A** (Node ID: `NODE-A`, Name: `Node A`)
   * **Phone 2**: Select **Node B** (Node ID: `NODE-B`, Name: `Node B (Relay)`)
   * **Phone 3**: Select **Node C** (Node ID: `NODE-C`, Name: `Node C`)

### Step 2: Form the Topology ($A \longleftrightarrow B \longleftrightarrow C$)

1. On **all three phones**, tap **"Start Mesh Networking"** on the Status screen.
2. Ensure the top status switches from **P2P** to active (green dot).
3. On **Phone 1 (Node A)**: Navigate to the **Neighbors** tab. Locate **Node B** under Discovered Peers and tap **"Connect"**.
4. On **Phone 3 (Node C)**: Navigate to the **Neighbors** tab. Locate **Node B** under Discovered Peers and tap **"Connect"**.
5. Verify connections on **Phone 2 (Node B)**:
   * Node B's active neighbors list now shows **Node A** and **Node C**.
   * Note: Node A and Node C are *not* directly connected to each other; Node B is the bridge!

### Step 3: Send Multi-Hop Message ($A \to B \to C$)

1. On **Phone 1 (Node A)**:
   * Navigate to the **Messaging** tab.
   * Select **Node C** (`NODE-C`) as the Destination.
   * Set TTL to `5`.
   * Type message: `"Hello Node C from Node A through Node B!"`.
   * Tap **"Send via MeshLink"**.

### Step 4: Verify the Forwarding Flow Across All Devices

* **On Phone 1 (Node A)**:
  * Message history shows outgoing packet with `Hops: 0`, `TTL: 5`, `Route: [NODE-A]`.
  * Logs show `[SEND]` event dispatched to Node B.
* **On Phone 2 (Node B - Relay)**:
  * Open the **Logs** tab.
  * Live log records:
    ```text
    [ROUTING-IN] Processing packet 'MSG-XXXX' (Src: NODE-A, Dst: NODE-C)
    [RELAY] Node B acting as relay: forwarding 'MSG-XXXX' to NODE-C
    TTL decremented: 5 -> 4
    Hop count incremented: 0 -> 1
    Route history: NODE-A -> NODE-B
    ```
* **On Phone 3 (Node C - Destination)**:
  * Open the **Messaging** tab.
  * Message appears in inbox with **"DELIVERED HERE"** badge.
  * Tap **Info (Inspect)** to view full packet details:
    * Source: `NODE-A`
    * Destination: `NODE-C`
    * Final Hop Count: `1`
    * Remaining TTL: `4`
    * Route: `NODE-A ➔ NODE-B ➔ NODE-C`
    * Payload: `"Hello Node C from Node A through Node B!"`

---

## 8. In-Process Simulation Mode (Single Device Demonstration)

If three physical phones are not immediately available during evaluation:

1. Launch MeshLink on an emulator or single device.
2. Toggle the **SIM** switch in the top app bar (or navigate to the **3-Node Sim** tab).
3. Tap **"Trigger A ➔ B ➔ C Demo"**.
4. The visual diagram illustrates the packet passing from Node A through Node B's active `RoutingEngine` instance down to Node C in real-time.
5. Inspect the live event log on the **Logs** tab to see every routing decision step executed.

---

## 9. Automated Unit Tests

Run unit tests via command line:

```bash
./gradlew test
```

Or right-click `app/src/test/java/com/meshlink/app/domain/routing/RoutingEngineTest.kt` in Android Studio and select **Run 'RoutingEngineTest'**.

### Test Suite Summary

1. `test1_destinationReached_deliversPayload()`: Asserts that matching `destinationId` results in `ForwardingDecision.Deliver`.
2. `test2_relayNode_forwardsPacket_andUpdatesTtlAndHops()`: Asserts that non-matching destination decrements TTL, increments hop count, and selects next hop.
3. `test3_duplicatePacket_dropped()`: Asserts that re-receiving identical `messageId` returns `ForwardingDecision.DropDuplicate`.
4. `test4_expiredTtl_dropped()`: Asserts that `TTL <= 1` results in `ForwardingDecision.DropTtlExpired`.
5. `test5_threeNodeMultiHopFlow_success()`: Simulates complete 3-node pipeline ($A \to B \to C$) and verifies final packet delivery.
6. `test6_loopAvoidance_excludesRouteHistoryAndPreviousHop()`: Verifies loop prevention prevents echoing to previous hops or historical nodes.
7. `test7_noRouteAvailable_returnsDropNoRoute()`: Verifies graceful degradation when no next-hop neighbor exists.

---

## 10. Known Limitations (Review II) & Roadmap

### Current Scope Boundaries (Review II)
* Routing uses controlled static neighbor forwarding / direct destination matching rather than dynamic route discovery protocols (AODV/DSR).
* Packets are unencrypted (cleartext payload for inspection and verification).
* No automatic multi-path route recovery or acknowledgments yet.

### Future Milestones
* **Review III**: Dynamic routing tables, hello beacons, and neighbor link quality metrics (RSSI).
* **Review IV**: Dynamic AODV route discovery (`RREQ`/`RREP`), route error handling (`RERR`), and link failure recovery.
* **Review V**: End-to-end cryptographic encryption (AES-GCM / Curve25519), emergency SOS broadcasts, offline file sharing, and performance benchmarking (latency, throughput, delivery ratio).

---

## 11. Author & License

* **Project**: MeshLink Version 0.1 (Review II Prototype)
* **Status**: Complete & Verified for Academic Review II
