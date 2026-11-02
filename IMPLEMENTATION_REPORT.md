# MeshLink Android implementation handoff — 1 November 2026

Implemented locally; pending physical BLE verification. No commits, branches, pushes, or GitHub changes were made.

## Implementation

- BLE GATT uses a dedicated MeshLink service (`a17e0001-83b8-4f28-9e44-1c9c8de4a630`), filtered scanning, compact identity-hash advertising, and full identities exchanged through CRC-protected HELLO frames. Stable UUID identities persist across launches and are excluded from backup/transfer.
- A deterministic identity-hash ordering selects the central for each pair. A connection policy caps simultaneous sessions at four by default, bounds retries to five with exponential backoff, deduplicates discoveries, and expires stale sightings. Connection setup has a 30-second watchdog. READY requires both received HELLO and successful local HELLO transmission.
- Client writes use responses; peripheral transfers use indications. Operations wait for callbacks with an eight-second operation timeout. Per-peer serialization and a server indication mutex prevent fragment interleaving/shared-characteristic races. BLE failures have no Nearby fallback.
- Fragment datagrams carry magic, transfer ID, index, count, and total frame length. Reassembly supports out-of-order/duplicate fragments, rejects conflicting/malformed fragments, expires at ten seconds, and permits four incomplete transfers per peer. The complete reconstructed frame passes version/type/exact-length and CRC validation before decoding or routing.
- CRC remains polynomial 0x1021, initial 0xFFFF, no reflection, XOR-out 0; the 123456789 vector is 0x29B1. The inner version/type/length/payload/CRC format remains intact. Each hop reserializes and recomputes CRC.
- Android-independent AODV-style routing implements RREQ, reverse routes, destination-generated RREP, sequence freshness, equivalent-freshness hop preference, route expiry, precursors, RERR, bounded pending sends, and rediscovery. Data uses one selected next hop rather than flooding. Intermediate route replies are deliberately not used.
- Separate bounded node announcements share identities across the mesh without proactively calculating routes. Remote node knowledge does not imply reachability.
- Destination ACKs travel back along the actual data path. The UI distinguishes queued transfer, next-hop ATT receipt, and destination acknowledgment. Acknowledged route history/hops/TTL are reflected at the sender. ATT receipt is not proof of application CRC acceptance.
- The dark UI retains destination-only selection, adds known/direct/routed/unknown labels and route information, and removes hardcoded production B/C destinations. The original three-node demonstration remains separate; new AODV scenarios run in unit tests.
- Networking stops when the activity leaves the foreground. Bluetooth-off/permission loss stops networking with an error; the user restarts after restoring availability.

## Important files

Paths below are relative to `/Users/Dhuna/MeshLink`.

| File | Change |
|---|---|
| app/src/main/java/com/meshlink/app/data/transport/AndroidBleGattTransport.kt | Discovery, GATT callbacks, indications, HELLO readiness, fragmentation integration, diagnostics, corruption injection, cleanup |
| app/src/main/java/com/meshlink/app/data/transport/BleConnectionPolicy.kt | New bounded discovery/retry/capacity policy |
| app/src/main/java/com/meshlink/app/data/transport/framing/BleFragments.kt | New fragment encoding and bounded reassembly |
| app/src/main/java/com/meshlink/app/data/transport/framing/MeshLinkFrame.kt | Reject unknown frame types and trailing/incorrect lengths |
| app/src/main/java/com/meshlink/app/domain/routing/AodvEngine.kt | New pure AODV engine, announcements, pending sends, ACK processing |
| app/src/main/java/com/meshlink/app/domain/models/MessagePacket.kt | Optional routing-control and acknowledgment fields |
| app/src/main/java/com/meshlink/app/data/repository/MeshRepository.kt | Persistent identity, BLE/AODV integration, known nodes/routes/delivery state |
| app/src/main/java/com/meshlink/app/utils/JsonSerializer.kt | Reject unsafe packet-decoding fallback |
| app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshUiState.kt | Bluetooth, known-node, route, delivery state |
| app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshViewModel.kt | Observe new state and use known destinations |
| app/src/main/java/com/meshlink/app/ui/screens/HomeScreen.kt | Bluetooth/network state, known-node count, foreground guidance |
| app/src/main/java/com/meshlink/app/ui/screens/MessagingScreen.kt | Dynamic destinations, route panel, delivery status, accurate CRC labels |
| app/src/main/java/com/meshlink/app/ui/components/PermissionHandler.kt | BLE-specific permissions; legacy permissions only in Nearby mode |
| app/src/main/java/com/meshlink/app/MainActivity.kt | Foreground lifecycle cleanup |
| app/src/main/res/xml/backup_rules.xml; data_extraction_rules.xml | Exclude installation identity from backup/transfer; correct extraction-rule domains |
| app/src/test/java/com/meshlink/app/data/transport/BleConnectionPolicyTest.kt | New policy tests |
| app/src/test/java/com/meshlink/app/data/transport/framing/BleFragmentsTest.kt | New fragmentation/validation tests |
| app/src/test/java/com/meshlink/app/domain/routing/AodvEngineTest.kt | New deterministic routing/network tests |

## Final verification

Command: `ANDROID_HOME=/Users/Dhuna/Library/Android/sdk ./gradlew testDebugUnitTest lintDebug assembleDebug --offline`

Final result: **BUILD SUCCESSFUL**. Lint: **0 errors, 19 warnings** (dependency-update notices, unused resources, manifest attribute/label notices). Earlier lint errors were corrected. No dependency upgrades were made.

APK: `/Users/Dhuna/MeshLink/app/build/outputs/apk/debug/app-debug.apk` (approximately 16 MB).

Tests: **52 executed, 52 passed, 0 failed, 0 skipped**.

| Suite | Passed |
|---|---:|
| AodvEngineTest | 14 |
| BleConnectionPolicyTest | 2 |
| BleFragmentsTest | 6 |
| MeshLinkFramingTest | 7 |
| Crc16CcittTest | 7 |
| RoutingEngineTest (legacy) | 9 |
| MessagePacketTest | 2 |
| CrossPlatformPacketCompatibilityTest | 3 |
| BleFramingTest | 2 |

Coverage includes direct, three-node and four-node delivery; shorter eligible paths; diamond rerouting and remote-link RERR propagation; joining node announcements; CRC rejection; duplicate data/RREQ suppression; reverse routes; sequence freshness; expiration; pending timeout; TTL/loop rejection; acknowledged actual route; fragment duplicate/out-of-order/missing/timeout/invalid-index/oversize/conflicting-content cases; and retry/capacity/discovery expiry.

Reports:
- `/Users/Dhuna/MeshLink/app/build/reports/tests/testDebugUnitTest/index.html`
- `/Users/Dhuna/MeshLink/app/build/reports/lint-results-debug.html`

Not executed: Android instrumentation/UI tests, BLE radio tests, physical-device installation, throughput/battery/long-duration tests. `adb devices -l` returned an empty device list. No physical test is marked PASS.

## Known limitations

- This is an implemented prototype, not a physically validated transport. Vendor-specific simultaneous central/peripheral support, scan/advertising behavior, callback ordering, reconnection stability, and multi-connection capacity remain unverified.
- Four direct sessions per device is a configurable source-level policy, not a guarantee from the hardware. Connection selection is capacity-limited; it does not optimize global connectivity or rotate established links to repair partitions.
- Foreground only. Leaving the app or locking the screen can stop relaying. No foreground-service/background networking implementation was added. Bluetooth/permission recovery requires pressing Start again.
- Discovery stops retrying an endpoint after five failed attempts until networking restarts (or bounded retry records are evicted). Identity-hash collisions are theoretically possible; MAC addresses are used only as temporary endpoints.
- Routing supports up to 512 stored routes/known nodes, 64 pending outgoing messages, 2,048 entries per duplicate cache, a 16-hop control limit, 60-second route lifetimes, and 30-second announcements. These are bounds, not validated scale claims. AODV does not guarantee a globally shortest path under arbitrary timing.
- Source retries/discovery are bounded by a 35-second pending lifetime. There is no relay-side packet queue/local repair: a relay without a route sends an error; the origin retries. Messages can fail during repair. An ACK lost after delivery can cause the sender to report failure despite receipt. Duplicate delivery suppression is time-bounded (two minutes), not durable exactly-once delivery.
- Frame payload limit is 4,096 bytes; UI-originated message text is limited to 2,000 UTF-8 bytes. At low MTU/slow links, ten-second reassembly and delivery timeouts can reject large transfers; physical measurements must guide tuning. There is no fragment-level retransmission; a failed transfer leads to whole-message retry.
- CRC protects against accidental corruption only. There is no message authentication, encryption, or hostile-peer protection. ACKs are not cryptographic delivery proofs.
- New BLE fragment envelope, indication requirement, and dedicated service UUID require this build on all Android peers. Existing iOS/older BLE builds are not radio-protocol compatible; passing packet compatibility tests does not establish cross-platform BLE compatibility.
- UI rendering and the legacy three-node demonstration were not exercised on a device. Legacy simulation is not the new deterministic AODV test harness.

## Physical test procedure — all pending

1. Install the APK above on every test phone (Android 7/API 24 or later). Select **BLE GATT**, keep the app visible, enable Bluetooth, and grant requested permissions. On Android 11 or earlier, also enable system Location for BLE scanning. Record model/OS and use distinct display names A/B/C/D while keeping the automatically generated IDs. Disable Wi-Fi/mobile data to demonstrate infrastructure independence.
2. **Two phones:** press Start on both. Wait for each other to appear as connected neighbors, not merely discovered. Check HELLO/CRC/connection logs. Send A→B and B→A. Require destination reception and sender status “Delivered (destination ACK).” A transmit callback alone is insufficient.
3. **Three-node relay:** arrange physical range/shielding so neighbor lists show A↔B↔C and no A↔C connection. Keep B open. Wait up to one announcement interval for C to appear at A, or enter C’s exact node ID. Send A→C with TTL ≥2. Require path A,B,C, hop count 2, and TTL reduced by 2.
4. **Four or more:** add D with B↔C↔D connectivity and no shortcut from A to C/D. Verify neighbor lists before testing. Send A→D with TTL ≥3; require path A,B,C,D and three hops. Repeat in reverse. Add a further node and check announcement/route discovery. Proximity alone does not establish a forced chain: confirm actual neighbors.
5. **Automatic routing:** with no relay selection, send to a known remote destination. Inspect RREQ generation/forwarding, RREP reception/route installation, the route panel, and the acknowledged path. For two alternate paths, record route freshness and timing as well as hop count.
6. **Rerouting:** establish A↔B↔D and A↔C↔D without a direct A↔D link. Send once and identify the selected relay. Stop networking on that relay (or move it out of range). Check disconnect/RERR/invalid-route logs. Send again and require rediscovery through the remaining relay. A queued message may recover or fail within the bounded timeout; do not expect uninterrupted delivery.
7. **CRC rejection:** first establish a working route. Arm “Simulate CRC Corruption” at the sender and send a unique test message. The selected message and its retries remain deliberately corrupted for the test window; control messages and ACKs are unaffected. Require CRC FAIL at the next hop, no delivery/forwarding of that message, and eventual sender failure rather than a destination ACK. Send a fresh unarmed message to check recovery.
8. **Fragmentation:** send roughly 1,000 ASCII characters, then up to 2,000. Verify multiple fragments in BLE logs, complete-message reception, CRC PASS, matching content, and destination ACK. Repeat both directions and through a relay. Record negotiated MTU and elapsed time. A timeout is a failed physical test, not a pass.
9. **Lifecycle/recovery:** stop/start networking, toggle Bluetooth off/on, revoke/regrant permissions, leave/return to the app, and confirm no stale connected neighbors or false delivery statuses. Press Start after restoring prerequisites.

For every test retain sender/relay/destination logs, IDs, topology, OS/model, MTU, actual path, and PASS/FAIL evidence. No physical verification has been performed in this session.

## Git

Initial working tree was clean. All source changes remain local and unstaged. Only read-only Git status/diff commands were used. The final status snapshot is saved in `/Users/Dhuna/MeshLink/LOCAL_GIT_STATUS.txt`. No commit or push was performed.
