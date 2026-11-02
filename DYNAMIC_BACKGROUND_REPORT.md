# Dynamic simulator and background BLE phase

> Superseded for simulator functionality: at the user’s subsequent request, the Virtual Mesh Simulator screen, navigation, and runtime integration were removed. Its isolated protocol harness now lives only in unit-test sources. CRC error simulation and background BLE networking remain in the app.

Implemented locally on 2 November 2026. Existing uncommitted work was preserved. No commits, branches, pushes, or GitHub changes were made. Existing notification and theme functionality was retained without adding those separate features.

## Implementation

The existing local simulator already used independent production `AodvEngine` instances. This phase extends that implementation rather than replacing its routing with a scripted demonstration. It supports 3–32 nodes (including tested ten-node routing), unique IDs, renaming/removal/disabling, arbitrary links/endpoints, repeated messages, five presets, and isolated framing/fragmentation/CRC validation. Protocol events, tables, route history, TTL, hops, delivery, and ACK outcomes come from execution. Added explicit result metadata, speed selection, a horizontally scrollable topology sized to node count, the required “Virtual Mesh Simulator” title, and testable playback with cancellation-safe pause/step/reset.

`MeshNetworkingService` holds an idempotent session lease on the existing application repository. The Activity no longer stops networking in `onStop`; its simulator playback still pauses. A non-exported connectedDevice foreground service starts from the existing resumed, user-approved Bluetooth startup flow, promotes itself before starting networking, observes actual neighbor/activity flows, and displays an ongoing status notification with Open and Stop actions. UI state, routing tables, neighbors, messages, and statuses use the same application repository across Activity recreation. Duplicate start commands do not restart the transport.

Bluetooth turning off stops the session without a background dialog or automatic retry loop. Transport startup failure and genuine service destruction release networking resources through existing transport cleanup. Service collectors are canceled on destruction. No wake lock or new reconnection loop was added; existing bounded routing, fragments, queues, and BLE connection policy are retained. Temporary identity/demo reconfiguration is allowed a short settling interval before the service treats an inactive transport as shutdown.

Process-death policy is deliberately START_NOT_STICKY: reopen and explicitly Start again. Active GATT sessions and pending outgoing deliveries are not persisted across process death. No boot or hidden restart mechanism was added.

## Files changed in this phase

Paths below are relative to this repository. The full working-tree diff also contains substantial pre-existing user changes.

Modified:
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/meshlink/app/MainActivity.kt
- app/src/main/java/com/meshlink/app/data/repository/MeshRepository.kt
- app/src/main/java/com/meshlink/app/data/transport/SimulationTransport.kt
- app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshViewModel.kt
- app/src/main/java/com/meshlink/app/presentation/viewmodels/SimulationCommand.kt
- app/src/main/java/com/meshlink/app/ui/screens/SimulationScreen.kt
- app/src/test/java/com/meshlink/app/data/transport/DynamicSimulationTest.kt

Created:
- app/src/main/java/com/meshlink/app/service/MeshNetworkingService.kt
- app/src/main/java/com/meshlink/app/domain/network/MeshSession.kt
- app/src/main/java/com/meshlink/app/data/transport/SimulationPlayback.kt
- app/src/test/java/com/meshlink/app/domain/network/MeshSessionTest.kt
- app/src/test/java/com/meshlink/app/data/transport/SimulationPlaybackTest.kt
- app/src/androidTest/java/com/meshlink/app/BackgroundNetworkingTest.kt
- DYNAMIC_BACKGROUND_REPORT.md
- verification/2026-11-02/dynamic-background/build.log
- verification/2026-11-02/dynamic-background/git-status.txt

## Verification observed

Command: `./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --offline` using installed JBR 17 and Android SDK.

- 104 unit tests passed, zero failures, errors, or skips. Includes existing regression suites for framing, CRC, packet compatibility, AODV, connection policy, startup, preferences/notifications, and UI contrast.
- Simulator coverage includes chain/ten nodes, shorter route, relay failure/RERR/rediscovery, dynamic joining/removal, link replacement, TTL, history, ACK, CRC corruption, isolation, duplicate RREQ/reverse routes, repeated delivery and playback pause/step/reset.
- Pure session tests cover duplicate starts, idempotent cleanup, startup failure, and explicit retry. They do not prove Android lifecycle or radio reliability.
- On-device lifecycle test compiled. It covers Activity stop/recreation/return, duplicate Start, and explicit Stop/resource state. NOT RUN: no device attached. Requires Bluetooth enabled and existing runtime permissions granted. It does not substitute for a locked-screen multi-phone relay test.
- Lint passed: zero errors, 18 warnings. Warnings concern existing dependency versions, unused resources, API-31 backup attribute, redundant label, and KTX recommendation. Existing Kotlin warnings concern deprecated Compose icons/Divider and an unused Nearby variable. No dependency upgrade was attempted.
- Debug APK built successfully: `/Users/Dhuna/MeshLink/app/build/outputs/apk/debug/app-debug.apk`.
- `git diff --check` passed.
- `adb devices` returned no devices. All physical tests below remain NOT RUN; no physical PASS/FAIL is claimed.

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`, and the saved build log.

## Simulator instructions

1. Open Simulator → Virtual Mesh Simulator. Select Linear chain. Source/destination initially span all four devices.
2. Enter a message and TTL, then Transmit. Send starts playback. Use Pause and Step to inspect individual real packet deliveries, or select Slow/Normal/Fast. Reset clears execution and reloads the selected preset (custom edits are discarded).
3. Inspect chronological events, per-node routing tables, actual route, initial/remaining TTL, hops, CRC and ACK results. Scroll the diagram horizontally on larger meshes and use the named node/link editors below it.
4. Alternative routes provides two- and three-hop paths. Relay failure: deliver once, disable the relay named in the delivered route, Play to process RERR, then send again and inspect the alternative path.
5. Node joins: Add a virtual node, connect it to an existing node with the endpoint pickers, Play discovery, select it as destination, and transmit.
6. Custom topology starts with three unlinked devices. Add/remove/rename nodes, configure links, choose any endpoints, then transmit repeatedly without reloading the preset.

Virtual results are not physical BLE verification. The simulator starts paused with queued discovery events; virtual time advances only during Play or Step. Events retain the last 400 entries and delivered history the last 100 packets.

## Physical testing instructions and blank observation record

Install the debug APK on the phones, enable Bluetooth, grant existing Bluetooth/location permissions, and press Start Mesh Networking while the app is visible. Grant notifications if you want the status notification in the drawer. Use distinct node identities. Record phone model, Android version, battery policy, timestamps, message ID, route, hop count, TTL, destination receipt and source ACK for each attempt.

A — Two-device background reception (NOT RUN): connect both phones; press Home on the receiver, send a unique message from the sender, verify destination receipt and source ACK. Repeat with receiver locked/screen off, after 1 minute and after a longer idle interval. Reopen the receiver to inspect retained message history.

B — Three-device background relay (NOT RUN): enforce Phone 1 ↔ Phone 2 ↔ Phone 3 using distance or the existing controlled BLE demo allowlists on BOTH ends (exclude a direct Phone 1 ↔ Phone 3 link). Establish a route and verify Phone 2 appears in the actual route history. Background Phone 2 and send Phone 1 → Phone 3. Verify destination receipt and ACK, then repeat with Phone 2 locked. Check relay logs on reopening. A direct delivery does not prove relaying.

C — Service lifecycle (NOT RUN): Start, Home, reopen, verify active state and unchanged connections without a second “Starting networking” log. Rotate/recreate Activity and repeat. Stop using UI and notification action separately; verify notification disappears and peer disconnections/resources clear. Turn Bluetooth off while backgrounded; verify networking stops and reopen to enable Bluetooth through Activity interaction. Repeat with notification permission denied. Swipe away on each phone model and record observed behavior. After process termination or force-stop, reopen and explicitly Start again.

D — Simulator UI (NOT RUN on device): execute the four-node chain and inspect RREQ/RREP/DATA/ACK events, routing tables, history, TTL and delivery results; run relay-failure and node-joining flows, ten-node editing, speed/pause/step/reset, and repeated sends. Automated engine tests passed; device rendering/touch checks remain pending.

Do not mark any of A–D PASS until observed. Record failures with logs and the exact screen/background/lock state. The compiled instrumentation test can be selected with `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.meshlink.app.BackgroundNetworkingTest` after a suitable phone is attached.

## Android limitations and references

Foreground services improve lifecycle ownership but do not guarantee radio delivery. Android/OEM battery management, Doze, permissions, adapter state and process termination can interrupt BLE. Swipe-away behavior varies by manufacturer; test rather than assume. No networking is promised after force-stop. No battery optimization bypass or unnecessary wake lock was added.

Android 8+ foreground-start promotion and Android 12+ visible user-start restrictions are respected. Android 14 connectedDevice type and permissions are declared. Android 13 notification denial does not block an otherwise permitted foreground service: the service notice may be in Task Manager instead of the notification drawer. Android may kill the process without onDestroy; OS resources are then reclaimed and a fresh Start is required. Older Android versions still require their existing location/Bluetooth permissions and device-specific scanning checks.

Official guidance:
- https://developer.android.com/develop/connectivity/bluetooth/ble/background
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/ui/compose/notifications/notification-permission

## Git

The working tree remains dirty with pre-existing edits plus this phase. Full read-only status is saved in `verification/2026-11-02/dynamic-background/git-status.txt`. Nothing was staged, committed, pushed, branched, or changed on GitHub. Work is stopped for user testing feedback.
