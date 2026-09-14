# MeshLink iOS

**MeshLink** is a decentralized, infrastructure-independent peer-to-peer ad-hoc mesh networking application for iOS. It enables devices to discover nearby peers, establish wireless links, and exchange multi-hop messages with Time-To-Live (TTL) hop limits, route history tracking, and loop prevention—without cellular towers, Wi-Fi routers, or internet access.

This iOS application is **100% wire- and logic-compatible** with the reference Android MeshLink project.

---

## Key Features

- **Decentralized Multi-Hop Routing**: Packets propagate across intermediate relay nodes (`Node A ➔ Node B ➔ Node C`) with automatic TTL decrement, hop count increment, and route recording.
- **Cross-Platform Wire Compatibility**: Identical JSON serialization format between iOS and Android.
- **Hardware Bluetooth Low Energy (BLE) Mesh**: Dual-role `CBPeripheralManager` and `CBCentralManager` GATT implementation for infrastructure-free device-to-device communication.
- **Apple Multipeer Connectivity**: High-throughput zero-configuration peer transport for Apple-to-Apple high bandwidth testing.
- **3-Node Visual Simulation**: Built-in in-memory simulation running 3 independent `RoutingEngine` instances (`Node A`, `Node B (Relay)`, and `Node C (Destination)`) with live step visualization and telemetry.
- **Engineering-Grade Event Log**: Real-time terminal log console with severity filtering (`ALL`, `SUCCESS`, `INFO`, `WARN`, `ERROR`), microsecond timestamps, and packet inspection.
- **Packet Inspector**: Deep-dive packet breakdown showing Message ID, source, destination, TTL, hops, route breadcrumb, payload, and raw wire JSON with one-tap copy.
- **Modern Dark Engineering UI**: SwiftUI 5-tab interface styled in slate dark mode (`#0F172A`).

---

## Architecture Overview

```
                        ┌──────────────────────────────┐
                        │         SwiftUI UI           │
                        │ (Status, Neighbors, Messages,│
                        │    Logs, 3-Node Simulation)  │
                        └──────────────┬───────────────┘
                                       │
                        ┌──────────────▼───────────────┐
                        │        MeshViewModel         │
                        │    (Observable UI State)     │
                        └──────────────┬───────────────┘
                                       │
                        ┌──────────────▼───────────────┐
                        │        MeshRepository        │
                        │ (Node State & Event Dispatch)│
                        └───────┬──────────────┬───────┘
                                │              │
                ┌───────────────▼┐            ┌▼────────────────┐
                │  RoutingEngine │            │  MeshTransport  │
                │  (Pure Domain) │            │   (Interface)   │
                └───────┬────────┘            └────────┬────────┘
                        │                              │
                ┌───────▼────────┐      ┌──────────────┼──────────────┐
                │MessagePacket & │      │              │              │
                │SeenMessageMgr  │ ┌────▼─────┐ ┌──────▼──────┐ ┌─────▼──────┐
                └────────────────┘ │   BLE    │ │  Multipeer  │ │ Simulation │
                                   │Transport │ │  Transport  │ │ Transport  │
                                   └──────────┘ └─────────────┘ └────────────┘
```

---

## Project Structure

```
ios/
├── MeshLink.xcodeproj/              # Complete Xcode project
│   ├── project.pbxproj
│   └── project.xcworkspace/
├── MeshLink/                        # Native iOS Application
│   ├── App/
│   │   ├── MeshLinkApp.swift        # SwiftUI App Entrypoint
│   │   └── Info.plist               # Bluetooth & Network Permissions
│   ├── Domain/
│   │   ├── Models/
│   │   │   ├── MessagePacket.swift      # Wire-compatible packet model
│   │   │   ├── Node.swift               # Participating node identity
│   │   │   ├── ForwardingDecision.swift # Deterministic routing outcomes
│   │   │   └── NetworkLogEntry.swift    # Engineering log model
│   │   └── Routing/
│   │       ├── RoutingEngine.swift      # 5-step decentralized routing logic
│   │       └── SeenMessageManager.swift # Thread-safe LRU duplicate cache
│   ├── Data/
│   │   ├── Serialization/
│   │   │   └── PacketSerializer.swift   # JSON encode/decode utility
│   │   ├── Transport/
│   │   │   ├── MeshTransport.swift          # Abstract transport protocol
│   │   │   ├── CoreBluetoothTransport.swift # Real BLE GATT mesh transport
│   │   │   ├── MultipeerMeshTransport.swift # Apple Multipeer transport
│   │   │   └── SimulationTransport.swift    # 3-node in-memory simulation
│   │   └── Repository/
│   │       └── MeshRepository.swift         # Central state repository
│   ├── Presentation/
│   │   ├── Theme/
│   │   │   └── MeshColors.swift             # Slate dark color palette
│   │   ├── ViewModels/
│   │   │   ├── MeshUiState.swift            # UI state model
│   │   │   └── MeshViewModel.swift          # Main presentation ViewModel
│   │   ├── Components/
│   │   │   ├── NodeStatusBadge.swift
│   │   │   ├── LogLevelBadge.swift
│   │   │   ├── MetricCard.swift
│   │   │   ├── NodeIdentitySheet.swift
│   │   │   ├── PacketInspectorSheet.swift
│   │   │   └── BluetoothPermissionBanner.swift
│   │   └── Screens/
│   │       ├── MainTabView.swift            # 5-tab root container
│   │       ├── StatusScreen.swift           # Local Node status & metrics
│   │       ├── NeighborsScreen.swift        # Nearby peer discovery & links
│   │       ├── MessagingScreen.swift        # Packet composer & history
│   │       ├── LogScreen.swift              # Engineering event log console
│   │       └── SimulationScreen.swift       # 3-Node Multi-Hop Visualizer
│   ├── Utilities/
│   │   └── IdGenerator.swift                # Stable node & message ID generator
│   └── Resources/
│       └── Assets.xcassets/                 # App icons & Accent colors
├── MeshLinkTests/                           # Unit Test Suite
│   ├── MessagePacketTests.swift
│   ├── RoutingEngineTests.swift
│   ├── CrossPlatformCompatibilityTests.swift
│   ├── SeenMessageManagerTests.swift
│   └── run_tests.swift
└── README.md
```

---

## Build & Run Instructions

### Prerequisites
- Mac running macOS 13.0 (Ventura) or newer
- Xcode 15.0 or newer
- iOS 16.0+ Deployment Target (iPhone or iPad)

### Opening in Xcode
1. Open the project in Xcode:
   ```bash
   open ios/MeshLink.xcodeproj
   ```
2. Select your target device (e.g. **iPhone 15 Pro** or a connected physical iPhone).
3. Select Product ➔ **Build** (`Cmd + B`) or **Run** (`Cmd + R`).

### Running Unit Tests
In Xcode, press `Cmd + U` to execute all unit tests in `MeshLinkTests`.

---

## Physical Multi-Hop Demonstration (A ➔ B ➔ C)

### Setup:
1. **Device 1 (Node A - Source)**: Physical iPhone or Android device configured with Node ID `NODE-A`.
2. **Device 2 (Node B - Relay)**: Physical iPhone or Android device configured with Node ID `NODE-B`.
3. **Device 3 (Node C - Destination)**: Physical iPhone or Android device configured with Node ID `NODE-C`.

### Execution:
1. On each device, tap **Start Mesh Networking**.
2. Position Device B between Device A and Device C.
3. On Device A, open the **Messages** tab, set Destination Node ID to `NODE-C`, enter payload text, and tap **Send via MeshLink**.
4. Observe the live multi-hop event sequence:
   - **Node A**: Transmits packet to Node B (`TTL: 5`, `Hops: 0`, `Route: [NODE-A]`).
   - **Node B**: Intercepts packet, checks duplicate (`PASS`), checks destination (`NODE-B != NODE-C`), decrements TTL (`5 ➔ 4`), increments hopCount (`0 ➔ 1`), records route (`[NODE-A, NODE-B]`), and forwards to Node C.
   - **Node C**: Receives packet, verifies destination match (`NODE-C == NODE-C`), delivers payload to user, and records delivery in log console.

---

## Required Permissions (Info.plist)

| Key | Purpose |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | Bluetooth Low Energy scanning and central connections for mesh communication. |
| `NSBluetoothPeripheralUsageDescription` | Bluetooth Low Energy advertising to enable nearby devices to discover this node. |
| `NSLocalNetworkUsageDescription` | Local peer-to-peer transmission across local wireless links. |
| `UIBackgroundModes` | Background mesh packet routing (`bluetooth-central`, `bluetooth-peripheral`). |

---

## Technical Documentation
- [Architecture Deep Dive](IOS_MESHLINK_ARCHITECTURE.md)
- [Cross-Platform Compatibility Guide](CROSS_PLATFORM_COMPATIBILITY.md)
- [Unit Test Results](TEST_RESULTS.md)
