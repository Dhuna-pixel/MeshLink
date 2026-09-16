# MeshLink 2.0

> **Infrastructure-Independent Multi-Hop Device-to-Device Messaging**

MeshLink is a mobile application prototype that demonstrates how nearby consumer devices can communicate and forward messages across multiple hops without depending on internet connectivity, mobile data, a Wi-Fi access point, or a mobile hotspot.

The project is implemented for **Android and iOS** and focuses on making the application-level mechanics of multi-hop communication visible and understandable through node identities, relay forwarding, TTL handling, hop counting, route history, duplicate detection, event logs, and a 3-node simulation.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Problem Statement](#problem-statement)
- [Objectives](#objectives)
- [What MeshLink Demonstrates](#what-meshlink-demonstrates)
- [Important Clarification](#important-clarification)
- [Architecture](#architecture)
- [Message Packet](#message-packet)
- [Multi-Hop Routing](#multi-hop-routing)
- [TTL and Hop Count](#ttl-and-hop-count)
- [Duplicate Detection](#duplicate-detection)
- [Transport Layer](#transport-layer)
- [Cross-Platform Communication](#cross-platform-communication)
- [Node Identity](#node-identity)
- [Application UI](#application-ui)
- [Repository Structure](#repository-structure)
- [Technology Stack](#technology-stack)
- [Testing](#testing)
- [Physical Device Testing](#physical-device-testing)
- [How to Run Android](#how-to-run-android)
- [How to Run iOS](#how-to-run-ios)
- [Example Multi-Hop Scenario](#example-multi-hop-scenario)
- [Limitations](#limitations)
- [Future Work](#future-work)
- [Academic Project Context](#academic-project-context)
- [Project Status](#project-status)
- [Contributors](#contributors)

---

# Project Overview

Modern communication systems usually depend on infrastructure such as cellular networks, internet connectivity, Wi-Fi routers, or access points.

In situations such as:

- Network outages
- Disaster scenarios
- Remote locations
- Large crowded environments
- Temporary communication setups
- Infrastructure failures

nearby devices may still be physically capable of communicating with one another.

MeshLink explores the concept of using nearby devices as **relay nodes**.

For example:

```text
Node A  ─────>  Node B  ─────>  Node C
 Source          Relay         Destination
````

Node A does not need to communicate directly with Node C.

Instead:

1. Node A creates a packet for Node C.
2. Node B receives the packet.
3. Node B checks whether it should forward the packet.
4. The packet's TTL and hop count are updated.
5. Node B forwards the packet.
6. Node C receives and delivers the packet.

MeshLink exposes this process through the application's routing engine, logs, packet metadata, and visualization.

---

# Problem Statement

Communication becomes difficult when conventional network infrastructure is unavailable or unreliable.

Traditional messaging applications generally depend on:

- Internet connectivity
- Cellular networks
- Wi-Fi infrastructure
- Cloud servers
- Centralized communication services

MeshLink investigates a decentralized alternative where nearby devices can participate in forwarding messages.

The project focuses on a small-scale mobile prototype rather than attempting to replace existing cellular or internet infrastructure.

---

# Objectives

The main objectives of MeshLink are:

1. Discover nearby devices.
2. Establish device-to-device communication.
3. Support multi-hop message forwarding.
4. Use intermediate devices as relay nodes.
5. Maintain packet-level routing information.
6. Prevent duplicate packets and forwarding loops.
7. Use TTL to limit packet lifetime.
8. Track hop count and route history.
9. Provide useful routing and transport logs.
10. Demonstrate the concept on real Android and iOS devices.
11. Provide a controlled 3-node simulation.
12. Explore a common BLE-based transport between Android and iOS.

---

# What MeshLink Demonstrates

MeshLink currently demonstrates:

- Nearby node discovery
- Direct device-to-device messaging
- Multi-hop forwarding
- Relay nodes
- Packet identity
- Destination checking
- Duplicate detection
- TTL handling
- Hop counting
- Route history
- Previous-hop tracking
- BLE GATT communication
- Android Nearby Connections
- Android/iOS transport compatibility at the packet layer
- 3-node routing simulation
- Engineering event logs
- Automated routing and compatibility tests

The project is intentionally designed as a **small-scale educational and experimental prototype**.

---

# Important Clarification

Mesh networking itself is **not a new invention of MeshLink**.

Existing technologies and systems already explore related concepts, including:

- Bluetooth Mesh
- MANETs
- AODV
- DSR
- DTN
- Epidemic Routing
- Briar
- Bridgefy
- Meshtastic
- Other device-to-device and infrastructure-independent communication systems

MeshLink's contribution is instead focused on building and demonstrating a practical mobile prototype that makes the **application-level mechanics of multi-hop forwarding visible and testable**.

The project emphasizes:

- Packet-level routing
- Relay behavior
- TTL changes
- Hop-count changes
- Route history
- Duplicate prevention
- Cross-platform transport exploration
- Real-device experimentation
- Engineering logs
- Educational visualization

---

# Architecture

MeshLink follows a layered architecture.

```text
┌──────────────────────────────────────┐
│              UI Layer                │
│        SwiftUI / Jetpack Compose     │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│         Presentation Layer            │
│       ViewModel + UI State            │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│          Repository Layer             │
│          MeshRepository               │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│           Routing Layer               │
│          RoutingEngine                │
│      SeenMessageManager               │
│        ForwardingDecision             │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│          Transport Layer              │
│                                      │
│  Nearby Connections                  │
│  BLE GATT                            │
│  Simulation Transport                │
└──────────────────────────────────────┘
```

The routing logic is intentionally separated from the transport implementation.

This means the same routing concepts can be used with different communication mechanisms.

---

# Message Packet

MeshLink messages contain structured packet metadata.

A simplified packet contains:

```text
messageId
sourceId
sourceName
destinationId
destinationName
payload
ttl
hopCount
timestamp
routeHistory
previousHopId
```

Example:

```json
{
  "messageId": "MSG-001",
  "sourceId": "NODE-A",
  "sourceName": "Node A",
  "destinationId": "NODE-C",
  "destinationName": "Node C",
  "payload": "Hello from A",
  "ttl": 5,
  "hopCount": 0,
  "timestamp": 1720000000000,
  "routeHistory": [
    "NODE-A"
  ],
  "previousHopId": null
}
```

The packet contains enough information for the application-level routing engine to determine how the message should be processed.

---

# Multi-Hop Routing

The basic routing flow is:

```text
                 Message for C
                      │
                      ▼
                 ┌─────────┐
                 │ Node A  │
                 └────┬────┘
                      │
                      ▼
                 ┌─────────┐
                 │ Node B  │
                 │  Relay  │
                 └────┬────┘
                      │
                      ▼
                 ┌─────────┐
                 │ Node C  │
                 │   Dest  │
                 └─────────┘
```

At each receiving node, the routing engine evaluates the packet.

Conceptually:

```text
Receive packet
      │
      ▼
Is message already seen?
      │
   Yes ──────> Drop duplicate
      │
      No
      │
      ▼
Is this node the destination?
      │
   Yes ──────> Deliver
      │
      No
      │
      ▼
Is TTL valid?
      │
    No ───────> Drop packet
      │
      Yes
      │
      ▼
Update forwarding state
      │
      ▼
Select next hop
      │
      ▼
Forward packet
```

---

# TTL and Hop Count

MeshLink uses two related packet fields:

### TTL

**Time To Live (TTL)** limits how many forwarding transitions a packet can make.

### Hop Count

**HopCount** records how many network links the packet has traversed.

For MeshLink, the semantics are:

```text
Source:
TTL = 5
HopCount = 0

After A → B:
TTL = 4
HopCount = 1

After B → C:
TTL = 3
HopCount = 2
```

Therefore, for:

```text
A → B → C
```

the final packet at Node C should contain:

```text
TTL       = 3
HopCount  = 2

Route:
A → B → C
```

This is important because **two devices connected through one relay means two network links were traversed**.

---

## Example: Direct Communication

For:

```text
A → C
```

the packet begins as:

```text
TTL = 5
HopCount = 0
Route = [A]
```

After the direct transmission:

```text
TTL = 4
HopCount = 1
Route = [A, C]
```

---

## Example: One Relay

For:

```text
A → B → C
```

the state becomes:

### Node A

```text
TTL = 5
HopCount = 0
Route = [A]
```

### Node B

```text
TTL = 4
HopCount = 1
Route = [A, B]
```

### Node C

```text
TTL = 3
HopCount = 2
Route = [A, B, C]
```

---

## Example: Two Relays

For:

```text
A → B → C → D
```

the final destination receives:

```text
TTL = 2
HopCount = 3
Route = [A, B, C, D]
```

TTL decreases exactly once for every traversed link.

HopCount increases exactly once for every traversed link.

---

# Duplicate Detection

Mesh networks can encounter duplicate packets and forwarding loops.

MeshLink uses a `SeenMessageManager` to track recently processed message IDs.

Conceptually:

```text
Packet received
      │
      ▼
Is messageId already known?
      │
   ┌──┴──┐
   │     │
  Yes    No
   │     │
   ▼     ▼
 Drop   Process
```

For example:

```text
A → B → C
↑       │
└───────┘
```

If a packet returns to a node that has already processed the same `messageId`, the node can identify it as a duplicate and prevent unnecessary forwarding.

The implementation uses a thread-safe recently-seen message cache.

---

# Transport Layer

MeshLink separates routing logic from communication transport.

The project contains multiple transport implementations.

## Android Nearby Connections

Android can use Google Nearby Connections for Android-to-Android communication.

Nearby Connections provides a higher-level abstraction over the underlying device connectivity mechanisms.

MeshLink uses:

```text
P2P_CLUSTER
```

for the Android transport.

This transport is useful for demonstrating Android multi-device communication without manually implementing the complete BLE GATT lifecycle.

---

## BLE GATT

MeshLink also contains a BLE GATT transport for cross-platform experimentation.

The BLE architecture uses:

```text
Central
   │
   │ GATT connection
   ▼
Peripheral
```

A device may operate in both roles depending on the connection.

BLE communication requires explicit handling of:

- Peripheral advertising
- Central scanning
- GATT connection
- Service discovery
- Characteristic discovery
- Notification subscription
- MTU negotiation
- Packet fragmentation
- Packet reassembly
- Receive buffering
- Message framing
- Write queues
- Connection state
- Reconnection
- Multiple peers

This is one of the technically challenging parts of MeshLink.

---

# BLE Service

The current MeshLink BLE transport uses the following custom service:

```text
Service:
6E400001-B5A3-F393-E0A9-E50E24DCCA9E
```

Characteristics:

```text
TX:
6E400002-B5A3-F393-E0A9-E50E24DCCA9E

RX:
6E400003-B5A3-F393-E0A9-E50E24DCCA9E
```

The RX characteristic supports notification/read behavior and the TX characteristic is used for writes.

A CCCD is used for notification subscription:

```text
00002902-0000-1000-8000-00805f9b34fb
```

The implementation uses negotiated MTU information rather than assuming that every device has the same payload capacity.

---

# BLE Framing

BLE does not guarantee that one application message will arrive as one BLE callback.

Therefore, MeshLink uses:

```text
Application message
        │
        ▼
Serialization
        │
        ▼
Framing
        │
        ▼
BLE chunks
        │
        ▼
Transmission
        │
        ▼
Receive buffer
        │
        ▼
Frame reconstruction
        │
        ▼
Deserialization
```

The current framing approach uses newline-delimited structured packets.

This allows a stream of received chunks to be reconstructed into complete application-level packets.

---

# Cross-Platform Communication

One of the goals of MeshLink 2.0 is to support a topology such as:

```text
Android A
    │
    │ BLE
    ▼
iPhone B
    │
    │ BLE
    ▼
Android C
```

Here:

```text
A = Source
B = Relay
C = Destination
```

The routing layer remains platform-independent at the packet level.

The transport layer handles the platform-specific communication APIs.

Conceptually:

```text
Android A
   │
   ▼
Android BLE Transport
   │
   ▼
iPhone BLE Transport
   │
   ▼
Android BLE Transport
   │
   ▼
Android C
```

Cross-platform packet compatibility is tested using the common `MessagePacket` structure.

---

# Node Identity

MeshLink separates application identity from the device's Bluetooth name.

A node has:

```text
MeshLink Node ID
MeshLink Display Name
Bluetooth Device Name
```

These should not be treated as the same thing.

For example:

```text
Display Name:
Node B

MeshLink Node ID:
NODE-B-7A42

Bluetooth Name:
Galaxy Tab A8
```

The Bluetooth name is a platform/device property.

The MeshLink Node ID is the application's identity.

This distinction is important because Bluetooth names may be:

- Generic
- User-defined
- Different across devices
- Unavailable
- Changed by the user
- Unrelated to the application's identity

---

# Application UI

MeshLink provides several main screens.

## Status

The Status screen displays:

- MeshLink node identity
- Connection status
- Simulation status
- Active neighbors
- Discovered devices
- Messages transmitted
- Messages received
- Quick navigation

---

## Neighbors

The Neighbors screen displays discovered/connected nodes.

The goal is to make the local mesh topology understandable to the user.

---

## Messages

The Messages screen provides:

- Destination selection
- Target node ID
- TTL selection
- Message payload
- Send action
- Message history

Example:

```text
Destination:
Node C

TTL:
5

Payload:
Hello from Node A
```

---

## Logs

The Logs screen provides engineering information about routing and transport events.

Examples include:

```text
SOURCE
SEND
RELAY
DEST
ROUTING-IN
TX-CONFIRM
DROP
DUPLICATE
```

Log severity can include:

```text
SUCCESS
INFO
WARN
```

The logs are intended to make the internal behavior of the prototype visible during demonstrations and debugging.

---

## 3-Node Simulation

MeshLink contains a controlled 3-node visualization:

```text
┌─────────┐       ┌─────────┐       ┌─────────┐
│ Node A  │ ────> │ Node B  │ ────> │ Node C  │
│ Source  │       │ Relay   │       │   Dest  │
└─────────┘       └─────────┘       └─────────┘
```

The simulation demonstrates:

```text
Step 1:
A creates packet

TTL = 5
Hop = 0

        ↓

Step 2:
B receives and forwards

TTL = 4
Hop = 1

        ↓

Step 3:
C receives packet

TTL = 3
Hop = 2
```

The simulation is intended to use the same routing semantics as the actual routing engine rather than simply changing UI labels.

---

# Repository Structure

The repository contains both Android and iOS implementations.

```text
MeshLink/
│
├── android/
│
├── ios/
│   ├── MeshLink.xcodeproj/
│   ├── MeshLink/
│   │   ├── App/
│   │   ├── Domain/
│   │   │   ├── Models/
│   │   │   └── Routing/
│   │   ├── Data/
│   │   │   ├── Serialization/
│   │   │   ├── Transport/
│   │   │   └── Repository/
│   │   ├── Presentation/
│   │   │   ├── Components/
│   │   │   ├── Screens/
│   │   │   ├── Theme/
│   │   │   └── ViewModels/
│   │   └── Utilities/
│   │
│   └── MeshLinkTests/
│
├── docs/
│
├── README.md
│
├── IOS_MESHLINK_ARCHITECTURE.md
├── CROSS_PLATFORM_COMPATIBILITY.md
└── TEST_RESULTS.md
```

> The exact Android directory layout may differ depending on the current Gradle project structure.

---

# Android Architecture

The Android implementation uses:

- Kotlin
- Jetpack Compose
- MVVM
- Clean Architecture concepts
- StateFlow
- Repository pattern
- Structured packet serialization

Important components include:

```text
RoutingEngine
SeenMessageManager
MessagePacket
ForwardingDecision
MeshRepository
MeshTransport
NearbyConnectionsTransport
AndroidBleGattTransport
SimulationTransport
```

---

# iOS Architecture

The iOS implementation uses:

- Swift
- SwiftUI
- CoreBluetooth
- Observable state/view models
- Repository/transport separation
- Shared packet structure
- Application-level routing engine

Important components include:

```text
RoutingEngine
SeenMessageManager
MessagePacket
ForwardingDecision
MeshRepository
MeshTransport
CoreBluetoothTransport
MultipeerMeshTransport
SimulationTransport
```

---

# Technology Stack

## Android

```text
Language:
Kotlin

UI:
Jetpack Compose

Architecture:
MVVM + Clean Architecture concepts

State:
StateFlow

Communication:
Google Nearby Connections
BLE GATT

Serialization:
JSON

Testing:
JUnit
Android unit tests
```

---

## iOS

```text
Language:
Swift

UI:
SwiftUI

Communication:
CoreBluetooth
MultipeerConnectivity
Simulation Transport

Testing:
XCTest / Swift Testing
```

---

# Testing

MeshLink includes automated tests for important routing and compatibility behavior.

Tests cover areas such as:

- Packet creation
- Routing decisions
- Duplicate detection
- TTL handling
- Hop count
- Route history
- Direct delivery
- Relay forwarding
- TTL expiration
- BLE framing
- Cross-platform packet compatibility

---

# Routing Test Vectors

## Direct

```text
A → C

Initial:
TTL = 5
Hop = 0

Final:
TTL = 4
Hop = 1
Route = A → C
```

---

## One Relay

```text
A → B → C

A:
TTL = 5
Hop = 0

B:
TTL = 4
Hop = 1

C:
TTL = 3
Hop = 2
```

---

## Two Relays

```text
A → B → C → D

A:
TTL = 5
Hop = 0

B:
TTL = 4
Hop = 1

C:
TTL = 3
Hop = 2

D:
TTL = 2
Hop = 3
```

---

# Physical Device Testing

MeshLink has been tested on physical Android and iOS devices.

The project has been used to demonstrate:

```text
Android → Android
```

and to explore:

```text
Android → iPhone → Android
```

multi-hop communication.

The Android implementation also supports Nearby Connections as an Android-specific transport.

BLE GATT is being used as the common transport layer for cross-platform experimentation.

---

# Infrastructure Independence

MeshLink has been tested with mobile data disabled and Wi-Fi connectivity disabled, while communicating between nearby devices.

The intended claim is:

> MeshLink supports local device-to-device multi-hop communication without requiring internet connectivity, mobile data, a Wi-Fi access point, or a mobile hotspot.

This does **not** mean that every underlying radio mechanism is literally Bluetooth-only.

For example, Android Nearby Connections may use multiple underlying connectivity mechanisms depending on the platform and configuration.

The BLE transport specifically uses Bluetooth Low Energy GATT.

---

# How to Run Android

Open the Android project in Android Studio.

Make sure the required Android SDK and JDK are configured.

From the project directory:

```bash
./gradlew testDebugUnitTest
```

Run the Android build:

```bash
./gradlew assembleDebug
```

The generated APK can then be installed on a compatible Android device.

For physical-device testing, ensure that the required nearby-device/Bluetooth permissions are granted.

---

# How to Run iOS

Open:

```text
ios/MeshLink.xcodeproj
```

For the current working copy:

```text
~/MeshLink copy/ios/MeshLink.xcodeproj
```

It can be opened from Terminal with:

```bash
open "~/MeshLink copy/ios/MeshLink.xcodeproj"
```

In Xcode:

1. Select the `MeshLink` target.
2. Open **Signing & Capabilities**.
3. Enable automatic signing.
4. Select the appropriate development team.
5. Select a simulator or connected physical iPhone.
6. Build and run.

For a physical iPhone, the device may need:

- Developer Mode enabled
- Computer trust enabled
- Developer profile trusted

A Personal Team can be used for local development-device testing, subject to Apple's provisioning limitations.

---

# Example Multi-Hop Scenario

Consider three devices:

```text
┌───────────┐
│ Android A │
│  Source   │
└─────┬─────┘
      │
      │ BLE
      ▼
┌───────────┐
│  iPhone B │
│   Relay   │
└─────┬─────┘
      │
      │ BLE
      ▼
┌───────────┐
│ Android C │
│Destination│
└───────────┘
```

Node A wants to send:

```text
"Hello Node C"
```

to Node C.

The packet begins as:

```text
TTL = 5
HopCount = 0
Route = [A]
```

After reaching Node B:

```text
TTL = 4
HopCount = 1
Route = [A, B]
```

After reaching Node C:

```text
TTL = 3
HopCount = 2
Route = [A, B, C]
```

Node C then delivers the message to the application.

---

# Routing Responsibilities

The routing engine is responsible for application-level packet processing.

It handles concepts such as:

```text
Duplicate detection
Destination matching
TTL validation
Hop-count update
Route-history update
Previous-hop tracking
Forwarding decisions
```

The transport layer is responsible for communication mechanics.

This separation allows routing logic to remain independent from whether the underlying transport is:

```text
Nearby Connections
BLE GATT
Simulation
```

---

# Forwarding Decisions

A packet can conceptually result in one of several forwarding decisions:

```text
Deliver
Forward
DropDuplicate
DropTTL
```

For example:

```text
Destination == current node
        │
        ▼
      Deliver
```

or:

```text
Destination != current node
        │
        ▼
TTL valid?
   │         │
  No        Yes
   │         │
 Drop       Forward
```

---

# Current Design Philosophy

MeshLink intentionally avoids pretending to be a production-scale routing protocol.

The current implementation focuses on demonstrating the mechanics needed for multi-hop application-level forwarding.

It does not currently claim to implement a complete:

- AODV implementation
- DSR implementation
- Large-scale MANET routing system
- Production emergency communication infrastructure

Those are possible future directions rather than current claims.

---

# Limitations

The current prototype has several limitations.

## Scale

The project is intended for small-scale experimentation.

It is not designed to support large commercial mesh deployments.

---

## Routing

The current implementation focuses on application-level forwarding and does not implement a complete dynamic routing protocol such as AODV or DSR.

---

## Reliability

BLE connections can be affected by:

- Physical distance
- Radio interference
- Operating-system restrictions
- Background execution policies
- Device-specific BLE behavior
- Connection timing
- GATT state transitions

Therefore, real-world reliability may vary between devices.

---

## Platform Differences

Android and iOS expose different Bluetooth APIs and connection models.

Cross-platform communication requires careful handling of:

- GATT roles
- Service discovery
- Characteristics
- Notifications
- MTU
- Connection state
- Packet framing

---

## Security

The prototype should not currently be treated as a secure messaging application.

Additional security mechanisms such as:

- End-to-end encryption
- Authentication
- Key exchange
- Integrity protection
- Replay protection

would be required for a production deployment.

---

## Range

MeshLink does not magically increase radio range.

Multi-hop communication can extend the overall communication area only when additional reachable relay nodes are available.

---

## Infrastructure Replacement

MeshLink is not intended to replace:

- Cellular networks
- Internet infrastructure
- Emergency communication infrastructure
- Commercial messaging services

It is a prototype for studying infrastructure-independent device-to-device communication.

---

# Future Work

Potential future development includes:

- Dynamic route discovery
- Route recovery
- AODV-style routing
- DSR-style routing
- Automatic route selection
- Multipath routing
- Acknowledgements
- Reliability mechanisms
- End-to-end encryption
- Authentication
- Secure key exchange
- Emergency broadcast/SOS messaging
- File sharing
- Larger-scale performance testing
- Automated benchmarking
- Battery consumption analysis
- Latency analysis
- Packet delivery ratio measurements
- Route recovery measurements
- More robust background operation
- Improved BLE connection management

---

# Performance Metrics

The project can be evaluated using metrics such as:

### Packet Delivery Rate

```text
Delivered Packets
────────────────── × 100
Sent Packets
```

### End-to-End Latency

```text
Destination Receive Time
−
Source Send Time
```

### Hop Count

Number of network links traversed by the packet.

### Route Recovery Time

Time required to establish an alternative forwarding path after a link/node failure.

These metrics can be evaluated during future experimental testing.

---

# Academic Project Context

MeshLink is developed as an **Innovative Design Project (IDP)**.

The project focuses on:

```text
Problem Identification
        ↓
Literature Survey
        ↓
Requirement Analysis
        ↓
System Design
        ↓
Prototype Development
        ↓
Physical Testing
        ↓
Evaluation
        ↓
Further Development
```

The project is intended to demonstrate a functional prototype and practical understanding of decentralized communication concepts.

---

# Review Progression

The project development is aligned with the IDP review structure.

## Review I

Focus:

- Real-world problem
- Need
- Objectives
- Scope
- Expected outcomes
- Preliminary literature survey
- Proposed methodology

---

## Review II

Focus:

- Requirement analysis
- System design
- Component selection
- Initial prototype
- Routing implementation
- Multi-hop demonstration
- Feasibility testing

---

## Later Reviews

Future reviews can progressively demonstrate:

- Expanded prototype functionality
- Dynamic routing
- Cross-platform communication
- Performance evaluation
- Reliability testing
- Security improvements
- Larger experimental evaluation

---

# Team Roles

The project can be divided into three main technical areas.

## Technical / Prototype

Responsibilities:

- Routing engine
- Packet processing
- Transport implementation
- Debugging
- Device testing
- Multi-hop demonstration
- Technical explanation

---

## Research / Requirements

Responsibilities:

- Literature survey
- Existing systems
- Requirements
- Problem analysis
- Technology comparison
- Related work

---

## System Design / Documentation

Responsibilities:

- Architecture diagrams
- Flowcharts
- UI documentation
- Test tables
- Review presentations
- Project documentation

---

# Technical Demonstration Flow

A practical demonstration can follow this sequence:

```text
1. Show Node A
        ↓
2. Show Node B
        ↓
3. Show Node C
        ↓
4. Establish local connections
        ↓
5. Disable internet/mobile-data dependency
        ↓
6. Send message from A → C
        ↓
7. Show B acting as relay
        ↓
8. Show routing logs
        ↓
9. Show TTL change
        ↓
10. Show HopCount change
        ↓
11. Show Route History
        ↓
12. Show message delivered at C
```

For:

```text
A → B → C
```

the technical result to demonstrate is:

```text
Initial:
TTL = 5
Hop = 0

At Relay B:
TTL = 4
Hop = 1

At Destination C:
TTL = 3
Hop = 2
```

---

# Suggested Technical Explanation

A concise technical explanation of MeshLink is:

> MeshLink is a mobile prototype for infrastructure-independent multi-hop communication. A message is represented as a structured packet containing a source, destination, TTL, hop count, and route history. When an intermediate device receives a packet, the routing engine checks for duplicates, verifies the TTL, updates the forwarding state, and forwards the packet toward the destination. The routing logic is separated from the transport layer, allowing the prototype to experiment with Android Nearby Connections, BLE GATT, and simulation. Version 2.0 extends the prototype to Android and iOS and explores BLE as a common cross-platform transport.

---

# Why BLE GATT Is More Complex

Nearby Connections provides a relatively high-level communication abstraction.

BLE GATT requires explicit management of:

```text
Central/peripheral roles
        ↓
Scanning
        ↓
Advertising
        ↓
GATT connection
        ↓
Service discovery
        ↓
Characteristic discovery
        ↓
Notification subscription
        ↓
MTU negotiation
        ↓
Fragmentation
        ↓
Reassembly
        ↓
Message framing
        ↓
Write queues
        ↓
Connection lifecycle
```

This makes BLE significantly more involved from an implementation perspective.

The additional complexity is also useful for understanding how lower-level cross-platform communication works.

---

# Version History

## Version 2.0

MeshLink 2.0 expands the project from the original Android prototype into a broader Android + iOS prototype.

Major areas include:

- Android implementation
- iOS implementation
- BLE GATT transport
- CoreBluetooth transport
- Cross-platform packet compatibility
- Multi-hop routing
- TTL and hop-count tracking
- Route history
- Duplicate detection
- Simulation
- Engineering logs
- Automated tests
- Physical-device experimentation

---

## Version 0.1

The original prototype focused primarily on Android and demonstrated:

- Nearby device discovery
- Structured packets
- Multi-hop forwarding
- Relay behavior
- Duplicate prevention
- TTL handling
- Route logging
- 3-node simulation

Version 2.0 builds on those concepts and expands the transport and platform scope.

---

# Project Status

MeshLink 2.0 currently contains:

```text
✓ Android application
✓ iOS application
✓ Structured message packets
✓ Application-level routing engine
✓ Duplicate detection
✓ TTL handling
✓ Hop-count tracking
✓ Route history
✓ Android Nearby Connections transport
✓ Android BLE GATT transport
✓ iOS CoreBluetooth transport
✓ Simulation transport
✓ 3-node simulation
✓ Routing logs
✓ Automated Android tests
✓ Automated Swift tests
✓ Cross-platform packet compatibility tests
✓ Android build verification
✓ iOS build verification
✓ Physical-device experimentation
```

Cross-platform BLE interoperability should be considered an **experimental capability** until it has been verified reliably across the intended physical Android/iOS device combinations.

The project documentation should distinguish between:

```text
Automated verification
```

and:

```text
Physical interoperability verification
```

rather than treating a successful build or unit test as proof of real-device communication.

---

# Build Verification

Android unit tests:

```bash
./gradlew testDebugUnitTest
```

Android build:

```bash
./gradlew assembleDebug
```

iOS tests:

```bash
swift test
```

iOS simulator build:

```bash
xcodebuild \
  -project ios/MeshLink.xcodeproj \
  -scheme MeshLink \
  -destination 'generic/platform=iOS Simulator' \
  build \
  CODE_SIGNING_ALLOWED=NO
```

---

# Repository

GitHub:

https://github.com/Dhuna-pixel/MeshLink

---

# License

This project is currently an academic prototype.

A formal open-source license can be added if the project is intended for public redistribution.

---

# Acknowledgements

MeshLink builds upon concepts from established areas of networking and device-to-device communication, including:

- Mobile Ad Hoc Networks (MANETs)
- Delay/Disruption Tolerant Networking (DTN)
- Bluetooth Low Energy
- Bluetooth Mesh
- Multi-hop forwarding
- TTL-based packet lifetime
- Duplicate detection
- Opportunistic/device-to-device communication

The project is intended as an educational and experimental implementation of these concepts on consumer mobile devices.

---

# Conclusion

MeshLink 2.0 demonstrates how a mobile application can use nearby devices as communication relays without depending on conventional internet infrastructure.

The central idea is simple:

```text
Source
  │
  ▼
Relay
  │
  ▼
Destination
```

The technical implementation makes the process observable through:

```text
Packet Metadata
      +
Routing Engine
      +
TTL
      +
Hop Count
      +
Route History
      +
Duplicate Detection
      +
BLE / Nearby Transport
      +
Engineering Logs
      +
Real Device Testing
```

MeshLink is therefore best viewed as a **small-scale, cross-platform educational prototype for understanding and demonstrating application-level multi-hop device-to-device communication**.

```
```
