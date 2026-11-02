# MeshLink networking UX, dynamic simulator, and BLE demo handoff

Completed 2 November 2026. All prior local BLE/AODV changes were preserved. This report supersedes the earlier report's fixed-simulator and startup instructions; its physical BLE limitations still apply.

## Implemented changes

- Start now checks Bluetooth LE support and version-specific permissions, requests permission through Activity Result, and uses Android's ACTION_REQUEST_ENABLE prompt when Bluetooth is off. Approval continues startup automatically once the Activity is RESUMED. Denial, cancellation, delayed adapter enable, and permission changes have explicit states. Repeated taps cannot create duplicate sessions. No silent application Bluetooth activation is used.
- Dynamic Mesh Simulator replaces the fixed three-node UI and adapter. It supports up to 32 virtual nodes, unique IDs independent of names, add/remove/rename/disable/enable, editable links, selectable source/destination, message text, TTL, CRC corruption, reset, and all five requested scenarios. Each virtual node runs the production AodvEngine. FIFO simulated link events pass through the real frame encoder, fragmentation/reassembly, decoder, CRC check, and packet deserializer.
- The simulator has Play/Pause/Step, a network diagram, transmitting-edge highlights, disabled nodes, actual delivered route, route tables, final hop count/TTL, ACK-based delivery state, and chronological protocol events. The title explicitly says Simulation — Virtual Nodes. Its state, identities, messages, links, and clock are separate from physical networking.
- Controlled BLE Demo Mode is OFF by default and remains off across process restarts unless explicitly re-enabled. Configure an allowlist of exact neighbor IDs or paste a common ordered chain. The chain derives adjacent identities based on the local phone's ID. Outgoing scan candidates are checked against identity hashes; manual outgoing attempts, incoming HELLO identities, READY transitions, packet transmission/reception, and repository next-hop dispatch are restricted. An enabled empty allowlist denies all neighbors. Disabling clears restrictions and rebuilds sessions/routes if networking was active. All demo data uses real BLE GATT and AODV; no simulated transport is selected.
- Messages now uses a dropdown built from connected/known identities, excluding local and duplicate IDs. Names are shown alongside direct, routed, remote, or stale status. Selection survives refresh while the ID remains present. Remote knowledge becomes stale after 90 seconds without an announcement and expires after five minutes unless directly connected. Route discovery remains on demand. No virtual nodes enter this list.
- Fixed A/B/C presets and three-node labels were removed from Android production UI. Generic A/B/C routing test fixtures remain. Legacy Nearby is still an explicit separate physical option with no BLE fallback.
- Android Fragment was pinned to 1.6.2 because the prior transitive 1.1.0 dependency failed Activity Result lint compatibility checks. Repository event processing is serialized on the main dispatcher; BLE transfer operations remain asynchronous.

## Build and verification

Final command:
`ANDROID_HOME=/Users/Dhuna/Library/Android/sdk ./gradlew testDebugUnitTest lintDebug assembleDebug --offline`

Result: **BUILD SUCCESSFUL**.

APK: `/Users/Dhuna/MeshLink/app/build/outputs/apk/debug/app-debug.apk`

Tests: **78 executed, 78 passed, 0 failed, 0 skipped**. This includes the previous 52 regressions and 26 new tests:

| New suite | Tests | Coverage |
|---|---:|---|
| NetworkingStartupTest | 8 | Disabled/enabled/unsupported Bluetooth, permissions, accepted/declined enable, repeated Start, delayed state change, cancellation, revocation |
| DemoTopologyTest | 6 | Default/disabled behavior, allowlist identities and advertisements, consistent chain adjacency, forbidden shortcuts, empty/malformed configuration |
| DestinationsTest | 3 | Join/leave, deduplication, identical names with distinct IDs, local exclusion, selection retention, stale/route/direct status |
| DynamicSimulationTest | 9 | Four-node actual protocol/ACK flow, shorter path, relay failure/RERR/rediscovery, join/rename/remove, ten-node reverse forwarding, CRC rejection, TTL/reset, stale queued-link generations, independent simulator instances |

Lint: **0 errors, 20 warnings, 1 informational suggestion**. Remaining items concern newer dependency versions, unused resources, manifest attributes/label, and an available Kotlin extension. No lint baseline or suppression was used to hide the Activity Result compatibility failure.

Reports:
- `/Users/Dhuna/MeshLink/app/build/reports/tests/testDebugUnitTest/index.html`
- `/Users/Dhuna/MeshLink/app/build/reports/lint-results-debug.html`

### Emulator smoke checks

Installed and launched the final APK on the existing MeshLink_Test_Phone emulator (Android 37.1 image). Verified:

1. Status and simulator screens render and scroll.
2. Virtual chain Device 1 → Device 2 → Device 3 → Device 4 delivers with hop count 3, remaining TTL 5 from initial TTL 8, and destination ACK.
3. Bluetooth off + Start shows the Android system enable dialog.
4. Deny leaves networking stopped and displays that Bluetooth is required and the request was cancelled.
5. Allow turns Bluetooth on and automatically starts networking. The emulator caught an Activity resume-order issue; it was corrected with a lifecycle ON_RESUME observer and acceptance was retested successfully.
6. Physical Messages remains empty with zero peers despite virtual simulator nodes/messages.
7. AndroidRuntime error log was empty after the smoke checks. The emulator started for verification was closed afterward.

These were emulator UI smoke checks, not a connected instrumentation test suite and not physical BLE verification. Automated tests cover other scenarios; they were not all manually replayed in the emulator. Bluetooth permission-denial/version-matrix and multi-phone radio behavior still require device testing.

Screenshots are saved in `/Users/Dhuna/MeshLink/verification/2026-11-02/`.

## Launch the simulator

1. Open MeshLink and tap **Simulator** in the bottom bar. No Bluetooth or physical peers are needed.
2. Choose **Linear chain**, **Alternative routes**, **Relay failure**, **Node joins**, or **Custom topology**.
3. Pick source and destination, enter text, set TTL, and tap **Transmit through virtual mesh**. Play advances actual queued protocol events; Pause/Step allows inspection.
4. Inspect the final delivery status, actual route, hop count, TTL, route tables, and chronological events.
5. For relay failure, deliver once, disable a node in the observed route, play pending errors, and send again.
6. For joining, add a node, connect it to an existing endpoint using the link selectors, play discovery, then select it as the destination.
7. Rename/remove nodes or connect/disconnect arbitrary pairs below the diagram. Reset reloads the selected scenario with fresh virtual IDs and state.

## Configure four physical phones on one table

1. Install the SAME APK on all four phones. Keep MeshLink foreground on every relay. Use BLE GATT, not Nearby or the Simulator.
2. Give phones friendly names if desired. Do not replace their generated unique IDs. On each Status screen, use **Copy local ID** in the BLE Demo Topology panel.
3. Build one ordered list containing Phone 1's exact ID, then Phone 2's, Phone 3's, Phone 4's, one per line.
4. On EVERY phone turn **BLE Demo Topology Mode** ON. Until configured, the empty allowlist blocks every direct neighbor.
5. Keep **Use an ordered chain of all phone IDs** checked. Paste the SAME four-ID list on each phone and tap **Apply BLE topology**.
6. Verify the active permitted-neighbor list:
   - Phone 1: Phone 2 only.
   - Phone 2: Phones 1 and 3.
   - Phone 3: Phones 2 and 4.
   - Phone 4: Phone 3 only.
7. Press **Start BLE Mesh Networking** on each phone. Grant permissions and approve Bluetooth activation when requested. On older Android versions, Location services may also need to be enabled for scanning.
8. Verify actual connected neighbors match the chain. A forbidden phone may briefly establish a provisional incoming GATT connection before its identity is known; it is rejected at HELLO and never admitted as a READY routing neighbor.
9. Wait for remote announcements (about 30 seconds), then choose Phone 4 in Phone 1's Messages dropdown. Use TTL at least 3 and send.
10. Require reception at Phone 4, sender destination ACK, actual three-hop route, and forwarding/CRC logs on Phones 2 and 3. No PASS is claimed until these are observed on phones.
11. Disable Demo Mode on every phone to restore normal unrestricted automatic BLE discovery. Existing sessions/routes are rebuilt. Configuration mismatch or unknown IDs fails closed; it does not fabricate a link.

For alternative physical paths, use the explicit neighbor allowlists with reciprocal permission on each pair. Demo mode restricts local transport adjacency only; it does not encrypt/authenticate identities or control other apps' Bluetooth connections.

## Dynamic destination dropdown

Messages shows friendly names with unique IDs retained internally. Direct neighbors are prioritized; remote entries show valid route/hop count when available, otherwise on-demand discovery or stale reachability. Known does not mean reachable. Entries refresh while discovery runs; expired non-neighbors disappear and an invalid selection clears. Empty lists disable sending. The dropdown is height-bounded and scrollable.

## Files changed in this pass

All paths below are under `/Users/Dhuna/MeshLink/`.

| File | Purpose |
|---|---|
| app/build.gradle.kts | Fragment compatibility dependency |
| app/src/main/java/com/meshlink/app/MainActivity.kt | System permission/enable launchers, lifecycle continuation, Bluetooth broadcast handling |
| app/src/main/java/com/meshlink/app/domain/network/NetworkingStartup.kt (new) | Testable single-start transaction state machine |
| app/src/main/java/com/meshlink/app/domain/network/DemoTopology.kt (new) | Exact-ID/hash allowlist and common-chain validation |
| app/src/main/java/com/meshlink/app/domain/network/Destinations.kt (new) | Dynamic destination/status/selection logic |
| app/src/main/java/com/meshlink/app/data/transport/AndroidBleGattTransport.kt | Demo policy enforcement at discovery, handshake, and transfer boundaries |
| app/src/main/java/com/meshlink/app/data/transport/SimulationTransport.kt | Replaced fixed scripted simulator with shared-engine virtual link adapter |
| app/src/main/java/com/meshlink/app/domain/routing/AodvEngine.kt | Display-name announcement update and pending-send cancellation for virtual node failure |
| app/src/main/java/com/meshlink/app/data/repository/MeshRepository.kt | Physical/simulator separation, demo configuration, stale identities, serialized physical events |
| app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshViewModel.kt | Simulator commands/playback, startup/UI state, demo configuration, dropdown refresh |
| app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshUiState.kt | Startup, demo, and destination state; removed obsolete fixed-simulator state |
| app/src/main/java/com/meshlink/app/presentation/viewmodels/SimulationCommand.kt (new) | UI-to-ViewModel simulator actions |
| app/src/main/java/com/meshlink/app/ui/components/DestinationPicker.kt (new) | Shared bounded destination dropdown |
| app/src/main/java/com/meshlink/app/ui/components/DemoTopologyPanel.kt (new) | Physical demo enable/configuration/copy-ID panel |
| app/src/main/java/com/meshlink/app/ui/components/PermissionHandler.kt | Passive permission status; Start owns prompts |
| app/src/main/java/com/meshlink/app/ui/components/NodeIdentityDialog.kt | Removed fixed A/B/C presets; preserve exact-case IDs |
| app/src/main/java/com/meshlink/app/ui/screens/HomeScreen.kt | Startup status, physical demo panel, updated labels |
| app/src/main/java/com/meshlink/app/ui/screens/MainScreen.kt | Simulator navigation and independent state wiring |
| app/src/main/java/com/meshlink/app/ui/screens/MessagingScreen.kt | Dynamic physical-node dropdown and empty state |
| app/src/main/java/com/meshlink/app/ui/screens/SimulationScreen.kt | Interactive dynamic topology, playback, outcomes, and event log |
| app/src/main/java/com/meshlink/app/ui/theme/Color.kt | Removed obsolete node-specific comment |
| app/src/test/java/com/meshlink/app/domain/network/NetworkingStartupTest.kt (new) | Startup tests |
| app/src/test/java/com/meshlink/app/domain/network/DemoTopologyTest.kt (new) | Demo restrictions tests |
| app/src/test/java/com/meshlink/app/domain/network/DestinationsTest.kt (new) | Dropdown model tests |
| app/src/test/java/com/meshlink/app/data/transport/DynamicSimulationTest.kt (new) | Actual simulator integration tests |
| UX_SIMULATION_REPORT.md; LOCAL_GIT_STATUS.txt; verification/2026-11-02/ | Handoff, read-only Git snapshot, emulator evidence |

## Limits and pending physical tests

- No physical Android phone was connected. Real two-/four-phone GATT, controlled chain exclusion, actual relay forwarding, radio reconnect stability, throughput, and Android-version-specific prompts remain pending.
- Foreground only: backgrounding/locking can stop physical networking. Simulation playback pauses on Activity stop; its state remains in memory, not durable across process death.
- Simulator supports 32 nodes but four- and ten-node integrations were tested; dense/large topologies are not performance guarantees. Its deterministic FIFO links and accelerated virtual clock do not model BLE radio timing, interference, or loss. Queues/history are bounded (4,096 envelopes, 400 events, 100 message outcomes).
- AODV freshness and first-arrival timing can affect routes; no globally shortest-path guarantee. Production TTL, route/ACK deadlines, frame sizes, CRC security limitations, and four-session BLE policy from the prior implementation remain.
- Demo settings are session-only and intentionally reset OFF on process restart. Every participating phone must opt in and have a consistent exact-ID list. Renaming does not change identity; editing an ID requires updating configuration on the other phones.
- Topology editing and full populated physical dropdown behavior are unit-tested; not all controls were manually exercised on the emulator. The virtual selection controls and physical empty state were visually inspected.

## Git

All changes remain local and unstaged, including the previous implementation pass. No staging, commit, push, branch, checkout, reset, merge, rebase, pull, or GitHub operation occurred. Final read-only `git status --short` output is saved in `/Users/Dhuna/MeshLink/LOCAL_GIT_STATUS.txt`.

Work stopped after verification and handoff; waiting for user testing feedback.
