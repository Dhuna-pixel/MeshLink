> Historical record: the obsolete Apple-platform source and build projects were removed during the Android-only cleanup. Commands and source paths below describe the earlier implementation.

# MeshLink Cross-Platform Compatibility Guide

This document specifies the wire compatibility, serialization standards, transport requirements, and real-world interoperability matrix between **Android MeshLink** and **iOS MeshLink**.

---

## 1. Wire Format Compatibility

MeshLink uses structured JSON UTF-8 payloads over physical transport links. Both Kotlin (Android) and Swift (iOS) implementations use matching schema definitions.

### Field-by-Field Mapping

| Field Name | Kotlin Type (Android) | Swift Type (iOS) | Mandatory / Default | Description |
|---|---|---|---|---|
| `messageId` | `String` | `String` | Mandatory | Unique ID of packet (e.g. `MSG-8B12C3`) |
| `sourceId` | `String` | `String` | Mandatory | Originating node ID (e.g. `NODE-A`) |
| `sourceName` | `String` | `String` | Default: `""` | Originating human-readable name |
| `destinationId` | `String` | `String` | Mandatory | Final target node ID (e.g. `NODE-C`) |
| `destinationName`| `String` | `String` | Default: `""` | Final target human-readable name |
| `payload` | `String` | `String` | Mandatory | Content string (text/payload) |
| `ttl` | `Int` | `Int` | Default: `5` | Time-to-Live hop counter |
| `hopCount` | `Int` | `Int` | Default: `0` | Number of hops traversed |
| `timestamp` | `Long` | `Int64` | Epoch Milliseconds | Creation epoch timestamp in ms |
| `routeHistory` | `List<String>` | `[String]` | Default: `[]` | Node IDs in traversed path |
| `previousHopId`| `String?` | `String?` | Default: `null` / `nil` | Node ID of preceding transmitter |

### Example Wire Payload

```json
{
  "messageId": "MSG-A1B2C3",
  "sourceId": "NODE-A",
  "sourceName": "Node A",
  "destinationId": "NODE-C",
  "destinationName": "Node C",
  "payload": "Hello Node C from Node A across Node B!",
  "ttl": 4,
  "hopCount": 1,
  "timestamp": 1700000000000,
  "routeHistory": [
    "NODE-A",
    "NODE-B"
  ],
  "previousHopId": "NODE-B"
}
```

---

## 2. Cross-Platform Physical Transport (BLE GATT)

To establish wireless communication across Android and iOS devices without depending on proprietary OS-locked frameworks:

### GATT Service & Characteristic Specifications

- **Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **TX Characteristic (Write / WriteWithoutResponse)**: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
  - Used by Central devices to write data packets to Peripherals.
- **RX Characteristic (Notify / Read)**: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
  - Used by Peripherals to stream data packets to subscribed Centrals.

### MTU & Packet Framing
- Payloads exceeding the negotiated BLE MTU (typically 180–512 bytes) are chunked during transmission.
- Streams are delimited with a `\n` (`0x0A`) newline frame boundary to allow clean, non-blocking frame reassembly on reception.

---

## 3. Real-World Compatibility Status Matrix

| Scenario | Topology | Status | Technical Notes |
|---|---|---|---|
| **Android ➔ Android** | Android A ➔ Android B ➔ Android C | **WORKING** | Verified using Google Nearby Connections / BLE. |
| **iOS ➔ iOS** | iOS A ➔ iOS B ➔ iOS C | **WORKING** | Verified using CoreBluetooth BLE and Multipeer Connectivity. |
| **Android ↔ iOS** | Android A ➔ iOS B or iOS A ➔ Android B | **WORKING** | Supported via CoreBluetooth BLE GATT service integration. |
| **Android ➔ iOS ➔ Android** | Android A ➔ iOS B (Relay) ➔ Android C | **WORKING** | iOS Node B acts as intermediate GATT relay, decrements TTL, and forwards to Android C. |
| **3-Node Multi-Hop Simulation** | Node A ➔ Node B ➔ Node C | **WORKING** | Runs on both platforms using pure domain `RoutingEngine` instances. |

---

## 4. Platform Differences & Solutions

| Concern | Android Platform | iOS Platform | Cross-Platform Solution |
|---|---|---|---|
| **P2P Framework** | Google Nearby Connections API | CoreBluetooth / Multipeer | Clean `MeshTransport` abstraction with standardized `TransportEvent` stream. |
| **Serialization** | `kotlinx.serialization` + `Gson` | `Codable` (`JSONEncoder` / `JSONDecoder`) | Canonical JSON key names with sorted keys and UTF-8 encoding. |
| **Threading Model** | Kotlin Coroutines (`Flow`, `StateFlow`) | Swift Concurrency (`Task`, `Combine`, `@MainActor`) | Equivalent reactive stream mapping and thread-safe LRU locks. |
| **Background Execution** | Foreground Services (`Service`) | Background Modes (`bluetooth-central`, `bluetooth-peripheral`) | Configured in `Info.plist` with usage descriptions. |
