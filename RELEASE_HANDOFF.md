# MeshLink Android implementation handoff

Current as of 2026-11-08. The [README](README.md) is the feature, setup, behavior and limitations reference. This handoff supersedes the earlier local draft that described mandatory manual pairing and an in-app simulator.

## Delivered

- Chats/Nearby/Settings Android UI with editable profiles, persistent personal conversations, unread state, retry and notification navigation.
- Automatic signed-profile discovery; optional manual fingerprint verification and a green verified badge. Unverified participants can message.
- Real BLE central/peripheral GATT links with bounded retries, fresh TLS sessions on reconnect, CRC framing, AODV forwarding and authenticated destination ACKs.
- Independent encrypted delivery for selected recipients, Message everyone and persistent groups of up to eight people. Each group recipient can use a different route and has an independent delivery status.
- Creator-signed immutable group rosters; shared-group messages remain permitted between personally blocked members, while blocked personal messages and invitations are rejected.
- Group exit/re-add state persisted per group. Fresh invitations may re-add members after exits one and two; exit three locks out re-adding. Rejoin generations recover from missed notices without allowing stale messages to undo later exits.
- Confirmed local conversation/group deletion, selective notification/inbox cleanup, retained replay protection and retained group exit counts. Unsent departure notices remain protocol outbox records.
- Foreground mesh service, appearance and notification privacy preferences, diagnostics, routing/packet inspection and optional physical BLE allowlists.
- No virtual simulator or Google Nearby Connections transport in the Android production app. Simulation fixtures remain in tests.

## Validation

Latest completed checks: debug APK assembly, JVM tests, Android instrumentation and lint.

| Check | Result |
| --- | --- |
| JVM regressions | 129 passed; zero failures/errors |
| Single-device Android suite | 19 passed; one opt-in two-device test skipped (runner reports 20 total) |
| Lint | Zero errors; 18 warnings and one informational finding |
| Whitespace/diff check | Passed |

The Android suite includes the corrected deletion UI test, selective encrypted-history removal, notification fallback cleanup, replay suppression, group persistence, blocking/verification rules, exit/re-add policies, eight-member envelope size, encrypted multi-path routing, TLS record segmentation, and existing appearance/lifecycle tests. Multi-path tests use controlled logical routes on the Android runtime; they are not physical multi-phone radio benchmarks.

`PeerRecoveryTest` needs two separate test installations and is deliberately excluded from ordinary single-device runs. Earlier emulator recovery experiments are not evidence of comprehensive current physical-device acceptance. Physical range, battery, throughput, GATT interoperability across phone manufacturers and locked-screen relay reliability remain unmeasured by this suite.

## Packaging and compatibility

- Android version name `1.0`, version code `2`; minimum SDK 24, target/compile SDK 34; JDK 17 and the Gradle 8.4 wrapper.
- Debug artifact: `app/build/outputs/apk/debug/app-debug.apk`. Build outputs and local SDK configuration are ignored, not source-controlled releases.
- All participating phones should use the same updated APK. HELLO protocol version 2 alone does not establish support for current group envelopes and membership events.
- Legacy Swift/iOS code has not been migrated to this Android protocol and is not currently interoperable with it.
- No production signing/distribution pipeline or independent security certification is included.

## Security and operational limits

Public profiles prove key control; verified identity requires independent fingerprint comparison. TLS protects direct links, while destination-specific AES-GCM/RSA-OAEP/ECDSA envelopes protect message bodies and group context from relays. Routing metadata remains visible, paths are not guaranteed optimal, and delivery requires an available mesh. Endpoint wrapping keys are static, so endpoint envelopes lack forward secrecy against later compromise of those keys.

Local encrypted storage retains at most 500 journal records, including separate outgoing recipient copies. Groups have fixed rosters, an eight-member limit and a 64-definition cap. Message text is limited to 2,000 UTF-8 bytes. Retry windows, process lifetime, device clocks and Android background restrictions affect delivery. Consult the README before treating older implementation reports or screenshots as current behavior.
