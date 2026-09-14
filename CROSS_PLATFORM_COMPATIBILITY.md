# MeshLink Cross-Platform BLE Interoperability Specification

This document details the cross-platform Bluetooth Low Energy (BLE) GATT transport architecture, wire format, discovery protocol, framing specification, and hardware testing instructions for **Android ↔ iOS** multi-hop communication in MeshLink.

---

## 1. Why Android Nearby Connections and iOS CoreBluetooth Were Incompatible

Initially, the project used two separate transport implementations:
1. **Android**: Utilized the Google Play Services **Nearby Connections API** (`Strategy.P2P_CLUSTER`). Nearby Connections creates an abstracted wireless mesh by negotiating proprietary Wi-Fi Direct and Bluetooth Classic/BLE links managed internally by Google Play Services.
2. **iOS**: Utilized Apple's **CoreBluetooth** framework, which implements custom GATT peripheral advertising and central GATT client scanning.

Because Google Nearby Connections is a proprietary Google Play Services framework not available on iOS, direct physical Android ↔ iOS discovery and packet exchange did not work over Nearby Connections.

### The Solution: Common Symmetric BLE GATT Transport

To achieve true infrastructure-free, platform-agnostic device-to-device communication, MeshLink implements a common, symmetric **Dual-Role Bluetooth Low Energy (BLE) GATT Transport** on both Android (`AndroidBleGattTransport`) and iOS (`CoreBluetoothTransport`).

---

## 2. Protocol Specification

### 2.1 BLE GATT Service & Characteristics

Both platforms operate as **Dual-Role BLE devices** (simultaneously running as a GATT Server/Peripheral and GATT Client/Central):

| Component | UUID | Description | Properties / Permissions |
|---|---|---|---|
| **Mesh Service** | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | Primary MeshLink GATT Service | Primary Service |
| **TX Characteristic** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Client Write / Server Receive | `WRITE`, `WRITE_NO_RESPONSE` / `PERMISSION_WRITE` |
| **RX Characteristic** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Server Notify / Client Receive | `NOTIFY`, `READ` / `PERMISSION_READ` |
| **CCCD** | `00002902-0000-1000-8000-00805f9b34fb` | Client Characteristic Configuration | `READ`, `WRITE` |

### 2.2 Packet Wire Format (JSON Schema)

Every packet transmitted across the BLE link is encoded as a UTF-8 JSON object with the following fields:

```json
{
  "messageId": "MSG-XPLAT-001",
  "sourceId": "NODE-A",
  "sourceName": "Pixel 7 (Android A)",
  "destinationId": "NODE-C",
  "destinationName": "Galaxy S23 (Android C)",
  "payload": "Cross-platform multi-hop via iPhone B",
  "ttl": 5,
  "hopCount": 0,
  "timestamp": 1710000000000,
  "routeHistory": ["NODE-A"],
  "previousHopId": "NODE-A"
}
```

#### Field Definitions:
- `messageId` (`String`): Unique identifier for duplicate detection.
- `sourceId` (`String`): Node ID of the original sender.
- `sourceName` (`String`): Human-readable name of the sender.
- `destinationId` (`String`): Intended recipient node ID.
- `destinationName` (`String`): Intended recipient name.
- `payload` (`String`): Textual message data.
- `ttl` (`Int`): Time-To-Live hop limit (decremented at each relay).
- `hopCount` (`Int`): Traversed hop count (incremented at each relay).
- `timestamp` (`Long` / `Int64`): Epoch millisecond timestamp of packet creation.
- `routeHistory` (`List<String>` / `[String]`): Traversed node ID list (appended at each relay).
- `previousHopId` (`String?`): Node ID of immediate previous transmitter.

### 2.3 BLE Stream Framing & Chunking Protocol

BLE characteristic writes have MTU constraints (default ATT MTU = 23 bytes, yielding a 20-byte payload; negotiated MTU up to 512 bytes, yielding up to 509-byte payload).

To handle message packets of arbitrary length without truncation or packet concatenation:
1. **Framing Delimiter**: A newline byte `\n` (`0x0A`) is appended to the end of every serialized JSON packet.
2. **Chunking**: Outgoing data is split into sequential chunks of at most 180 bytes (or `MTU - 3` bytes).
3. **Pacing**: A 15ms link-layer pause is inserted between successive chunks to avoid BLE buffer overflow.
4. **Reassembly Buffer**: Inbound bytes on both sides are appended to a per-endpoint memory stream. Whenever `0x0A` is detected, the complete frame preceding the delimiter is extracted and decoded into a `MessagePacket`.

---

## 3. Platform Configuration & Permissions

### Android Requirements (Target SDK 34)
- **Manifest Permissions**:
  - `android.permission.BLUETOOTH_SCAN` (`usesPermissionFlags="neverForLocation"`)
  - `android.permission.BLUETOOTH_ADVERTISE`
  - `android.permission.BLUETOOTH_CONNECT`
  - `android.permission.ACCESS_FINE_LOCATION` (for legacy Bluetooth support on Android < 12)
- **Runtime Permission Handling**: `PermissionHandler.kt` prompts and validates all required permissions before activating the BLE stack.

### iOS Requirements (iOS 16+)
- **Info.plist Keys**:
  - `NSBluetoothAlwaysUsageDescription`: Explains BLE mesh usage.
  - `NSBluetoothPeripheralUsageDescription`: Explains GATT peripheral advertising.
  - `UIBackgroundModes`: Includes `bluetooth-central` and `bluetooth-peripheral`.

---

## 4. Selecting Transport Modes

MeshLink supports three independent transport layers selectable directly from the UI:

1. **Google Nearby Connections (Default for Android)**
   - Used for high-speed Android ↔ Android hardware clustering.
2. **BLE GATT Mode (Cross-Platform)**
   - Used for Android ↔ iOS cross-platform hardware communication.
3. **3-Node Virtual Simulation**
   - In-process testbed running three independent `RoutingEngine` instances for demonstrations and evaluation.

### Switching Mode on Android:
1. On the **Status (Home)** screen, scroll to **Transport Layer Selection**.
2. Tap **BLE GATT (Android ↔ iOS)**.
3. Tap **Start Mesh Networking**.

---

## 5. Step-by-Step Physical Test: Android A ➔ iPhone B (Relay) ➔ Android C

### Topology:
```
[ Android A ]       ══════►      [ iPhone B ]       ══════►      [ Android C ]
 Node ID: NODE-A                  Node ID: NODE-B                 Node ID: NODE-C
 Transport: BLE GATT              Role: RELAY                     Transport: BLE GATT
                                  Transport: BLE GATT
```

### Execution Steps:

#### Step 1: Configure Identities
1. On **Android Phone A**:
   - Open MeshLink $\to$ Settings icon $\to$ Set Node ID: `NODE-A`, Name: `Android A`.
   - On Status Screen $\to$ Select **BLE GATT Mode** $\to$ Tap **Start Mesh Networking**.
2. On **iPhone B**:
   - Open MeshLink $\to$ Pencil icon $\to$ Set Node ID: `NODE-B`, Name: `iPhone B`.
   - On Status Screen $\to$ Tap **Start Mesh Networking**.
3. On **Android Phone C**:
   - Open MeshLink $\to$ Settings icon $\to$ Set Node ID: `NODE-C`, Name: `Android C`.
   - On Status Screen $\to$ Select **BLE GATT Mode** $\to$ Tap **Start Mesh Networking**.

#### Step 2: Establish BLE Connections
1. In the **Neighbors** tab:
   - On **iPhone B**: Connect to discovered peers `NODE-A` and `NODE-C`.
   - Alternatively, on `NODE-A` and `NODE-C`, tap **Connect** to `NODE-B`.
2. Confirm `NODE-B` displays **Connected (2 active neighbors)**.

#### Step 3: Transmit Multi-Hop Message
1. On **Android Phone A**:
   - Navigate to **Messaging** tab.
   - Set Destination ID: `NODE-C`.
   - Enter payload: `"Hello Android C via iPhone B Relay!"`.
   - Set TTL: `5`.
   - Tap **Send**.

#### Step 4: Verify Logs on Each Device

- **On Android A (Source)**:
  ```
  [SEND [LOCAL]] Created message 'MSG-...' for destination 'NODE-C' with TTL 5
  [SEND [RELAY-OUT]] Broadcasting packet to connected neighbor(s)...
  [BLE-TX] Sent packet via BLE GATT Write to [NODE-B]
  ```

- **On iPhone B (Relay)**:
  ```
  [BLE-RX] Received packet 'MSG-...' from endpoint 'NODE-A'
  [ROUTING-IN] Processing packet 'MSG-...' (Source: NODE-A, Dst: NODE-C, TTL: 5, Hops: 0)
  [RELAY [FORWARD]] ACTING AS RELAY: Node 'NODE-B' is forwarding packet 'MSG-...'.
                    TTL decremented: 5 -> 4
                    Hop count incremented: 0 -> 1
                    Route history: NODE-A -> NODE-B
  [RELAY [TX]] Transmitting forwarded packet to next-hop 'Android C' [NODE-C]
  [BLE-TX] Sent packet via BLE GATT Write to [NODE-C]
  ```

- **On Android C (Destination)**:
  ```
  [BLE-RX] Received packet 'MSG-...' from endpoint 'NODE-B'
  [ROUTING-IN] Processing packet 'MSG-...' (Source: NODE-A, Dst: NODE-C, TTL: 4, Hops: 1)
  [DELIVER [DEST]] MESSAGE DELIVERED! Current node 'NODE-C' is the intended destination.
                   Payload: "Hello Android C via iPhone B Relay!"
                   Total Hops: 1
                   Route: NODE-A -> NODE-B -> NODE-C
  ```

---

## 6. Known Platform Limitations & Best Practices

1. **BLE Advertising Payload Limits**: Standard BLE legacy advertisements are limited to 31 bytes. MeshLink places the 128-bit Service UUID in the primary advertisement packet and the `NodeName|NodeId` string in the scan response / service data packet to avoid advertisement truncation.
2. **Peripheral Notification Throttling**: Rapid back-to-back notifications can saturate the BLE link layer. MeshLink includes a 15ms link-layer pause between chunk transmissions.
3. **Background BLE Throttling**: On iOS, when the app enters the background, CoreBluetooth disables unrestricted scan modes. For continuous mesh operation, keep MeshLink in the foreground during live test runs.
