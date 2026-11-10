> Archived README before the Android publication refresh; release claims below may be historical.

# MeshLink 1.0 for Android

An infrastructure-independent Android messaging application that uses Bluetooth Low Energy mesh-style forwarding and AODV-based routing to exchange encrypted messages across nearby devices without internet connectivity.

## Features at a glance

| Area | Available in the Android app |
| --- | --- |
| Offline networking | BLE discovery and advertising, direct connections, automatic multi-hop routing, reconnect-on-send, and bounded retries. No internet, server account, cellular data or Wi-Fi router is needed for messaging. |
| Personal conversations | Saved chats, sender names, timestamps, unread counts, delivery states, failure reasons, and retry without creating a second history entry. |
| Multiple recipients | Select recipients individually or use **Message everyone** for recently known, unblocked participants. Each destination receives a separate encrypted message and confirms independently. |
| Group conversations | Named groups of up to eight people, shared replies, member lists, per-recipient delivery details and confirmed paths, and retry of failed copies. |
| Profiles and verification | Editable display name and status; stable installation identity; optional fingerprint verification with a green check. Unverified participants can message and join groups. |
| Blocking | Block/unblock personal messages and invitations while retaining ordinary messages in an existing shared group. An unblocked mutual participant can create a group containing both people. |
| Exit and re-add | Explicit group exit, persisted per-group exit counts, fresh **Add back** invitations after exits one and two, and rejection of re-adding after exit three. |
| Delete | Confirmed local deletion of personal or group chat history and related notifications. Other people's copies, personal blocks, replay protection and exit counts are preserved. |
| Background use | A user-started foreground networking service, ongoing mesh status notification, incoming message alerts, and notification taps that open the relevant conversation. |
| Appearance and privacy | System/light/dark themes, message notification preferences, and lock-screen preview controls. |
| Diagnostics | Bluetooth and neighbor status, routing tables, filtered network events, packet details, and an optional physical BLE test allowlist. |
| Protection | Authenticated TLS links, separately encrypted endpoint messages and ACKs, signed profiles and group rosters, CRC validation, replay checks, and encrypted local message storage. |

The Android navigation is **Chats**, **Nearby**, and **Settings**. Diagnostic tools live under **Settings → Advanced**. There is no virtual network simulator in the Android app; simulation transports remain test fixtures only.

## Platform and release status

This README describes the current Android implementation (version name `1.0`, version code `2`). Use the same latest build on all participating phones: group envelopes and exit/rejoin events require features that older builds do not understand, even if their HELLO version is also 2.

The obsolete Swift sources, Xcode project and Swift package have been removed. Historical iOS documentation remains under `ios/` for reference. **Current Android–iOS messaging is not supported or verified.** Older compatibility documents, screenshots and implementation reports describe earlier milestones, not this release. See [the current handoff](RELEASE_HANDOFF.md) for the validation summary.

MeshLink is intended for local emergency coordination when an available BLE mesh connects participants. It is not a guaranteed emergency-alert service: a reachable route, running participant/relay devices, and compatible builds are still required.

## Join the emergency mesh

Use Android 7.0 or later (API 24+, target API 34) and phones that support BLE central, peripheral, advertising and GATT server roles. Install the same **secure protocol 2** build on every phone. On Android 12+, allow Bluetooth scan, connect and advertise permissions. Earlier Android versions use the Bluetooth and location permissions required for scanning. Android 13+ separately controls message and service notification visibility.

### Build and install

Use JDK 17, Android SDK Platform 34, and the included Gradle 8.4 wrapper. Open the repository in Android Studio, or configure `ANDROID_HOME`/a local `local.properties` for your SDK and run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It is debug-signed; no production signing or app-store release is configured here. SDK paths, build outputs, local environment files and signing credentials should stay out of version control. An upgrade with the same application ID and signing key preserves local identity and history. Uninstalling or clearing app data removes that installation's local data and keys.

### Start chatting

1. Open **Settings → Edit my profile**. Set a display name and optional short status. Both travel across the local mesh. Your installation keeps its existing ID and conversations.
2. Open **Nearby → Start mesh** on each phone. Grant Bluetooth permissions and enable Bluetooth when prompted. Use this build on all phones for automatic participation. Direct neighbors exchange signed public profiles over mutual TLS. Relays forward signed profiles so people several hops away can discover each other.
3. Tap **Chat** beside a participant or choose **Message everyone**. Everyone preselects the unblocked participants this phone has recently discovered, including remote participants. Review the recipients and tap **Send**. Each person receives a separately encrypted message with an individual delivery receipt. People who join later need a new message.
4. A green check identifies a contact whose keys you explicitly verified on this phone. People without a check can also communicate. To verify a contact, exchange public cards, compare full fingerprints independently, then use **Nearby → Pair and verify devices**.
5. Use **Block** in a conversation or in the person's **Nearby → Details**. Blocking stops personal messages and invitations from that identity, including relayed ones. It leaves relay service available and permits messages from blocked people inside a shared group you have already joined. A blocked creator cannot add you to a new group. Use **Unblock** to resume. Someone who creates an entirely new installation and identity can appear as a new participant.
6. **Chats** groups your saved messages and receipts. Only an authenticated destination acknowledgment marks a message delivered. A missing route, a phone outside the mesh, or a stopped relay can prevent delivery. Sending to a recently seen nearby participant also attempts to restore a disconnected direct link. Nearby distinguishes connected, connecting and disconnected devices. Queued messages wait up to two minutes for connection, transfer and confirmation; the ACK wait starts after the Bluetooth transfer completes. Failed messages show the reason and offer Retry.

## Group chats

Open **Chats → New group**, enter a name and select up to seven other participants (eight including yourself). Signed participant profiles must already be available. Group creation sends an encrypted invitation to each member; the group appears on their phone when an invitation or group message arrives. Everyone can reply in the same conversation. All participants need this updated build.

Group messages use separate encrypted unicasts and independently discovered AODV routes. A member reached directly and a member reached through relays can receive the same message over different paths. **Delivery details** shows each recipient's status and, after a destination acknowledgment, that recipient's confirmed path. **Retry failed** retries only unconfirmed copies, preserving message IDs. Delivery still depends on an available mesh path; offline members are not guaranteed eventual delivery after the bounded retry window.

The group name, member profiles and group context travel inside each recipient's encrypted envelope. The creator signs the immutable roster; only listed members can author accepted group messages. Creating a group does not mark members as fingerprint-verified. Personal blocking does not block ordinary messages or delivery receipts inside a shared active group. A blocked sender cannot send personal messages or add you to a group; a mutually unblocked participant can create a group with both of you. Fingerprint verification is optional for all group members. **Exit group** stops participation and broadcasts a signed departure when a mesh route is available. The exit count persists per group across restarts. After the first or second exit, an unblocked active member can choose **Members → Add back**; the invitation must match the latest exit, so stale invitations cannot undo a later departure. After the third exit, re-adding is permanently rejected on the departed phone and disabled for members who received the departure. Departure/rejoin notices update the active recipients. Subsequent signed messages carry the sender's rejoin generation so a missed notice does not permanently block their messages; stale messages cannot undo a later exit. Delivery remains dependent on the mesh. Adding entirely new people still requires a new group.

Group definitions and conversations persist in encrypted local storage. This version caps groups at 64 per installation, eight members per group, and 2,000 UTF-8 bytes of message text. The larger encrypted group envelope remains bounded, with 32 KB application records and a bounded per-link queue.

## Delete saved chats

Open a personal conversation or group and tap the trash icon, then confirm **Delete**. This removes that chat's saved messages and notifications from this phone, without removing anyone else's copy or changing personal blocks. A new personal message starts a new conversation. Deleting a group chat hides it locally without exiting; fresh group messages can show it again while you remain a member. Exit first if you want to stop receiving group messages, then delete the chat. Exit counters and the three-exit restriction remain intact. Unsent exit notices remain in the protocol outbox so other members can still learn of your departure. Deleted message replay records remain, preventing old retransmissions from restoring history.

## Physical BLE testing

In **Settings → Advanced → Physical BLE Test Configuration**, enable the direct-neighbor allowlist on each test phone. Apply the same ordered list of real phone IDs to build a chain, or configure reciprocal neighbors manually. This testing control defaults to off. Disconnect a relay, inspect the routing events under **Settings → Advanced → Diagnostics**, and check the sender’s acknowledged path. Turn off the allowlist to restore normal discovery.

A separate web-based simulator may be developed later.

## How a physical message travels

Android scans and advertises the MeshLink service, chooses a central/peripheral role and negotiates GATT characteristics and indications. The adapter fragments bounded CRC frames to the negotiated MTU. It checks the complete reassembled frame before feeding any bytes into TLS. Corrupt frames are rejected. Losing a TLS record ends that direct session.

TLS streams carry length-delimited application messages. Only after both peers authenticate and exchange a protocol-2 HELLO does the adapter expose the neighbor to AODV. Normal and controlled modes use the same secure transport. There is no network or cloud fallback.

AODV discovers a route with RREQ/RREP, caches next hops with sequence numbers and expiration, and propagates RERR when next hops fail. Relays update hop count, TTL and the recorded path. A fresh route can differ from the globally shortest route. A destination ACK carries the observed path back to the source. A next-hop ATT receipt alone leaves delivery pending.

The frame layout is version, type, payload length, payload and CRC-16-CCITT. Protocol 2 uses TLS frame type 4 inside the existing version-1 frame format. CRC uses polynomial `0x1021`, initial value `0xFFFF`, no reflection, and no final XOR. The check value for `123456789` is `0x29B1`.

## Trust and privacy

- **Identity.** Android Keystore holds a P-256 ECDSA signing key and a separate RSA-2048 decryption key. The fingerprint covers the versioned pairing card, including both public keys and the node ID. The app stores manual pins with Keystore-backed AES-GCM. Operators must compare fingerprints through an independent channel.
- **Direct links.** Android’s TLS 1.2 provider performs mutual certificate authentication with device certificates, ephemeral ECDH, ECDSA and AES-128-GCM. Before admitting a new link, MeshLink binds the signed public profile to the exact TLS peer certificate. It remembers first-seen keys and refuses conflicting replacements. Legacy peers need existing matching manual pins. TLS supplies transcript authentication, key derivation, key confirmation, directional record keys and sequencing. Each GATT session creates a new TLS context. A one-hour connection limit triggers fresh authentication on reconnection. Packet and handshake queues are bounded.
- **Private messages and ACKs.** Each endpoint envelope has a fresh random AES-256 key and 96-bit GCM IV. RSA-OAEP wraps that key for the destination, with SHA-256 and an explicit MGF1-SHA1 parameter for Android Keystore compatibility. ECDSA-SHA256 authenticates the wrapped key, ciphertext, nonce and immutable metadata. The version, message ID, endpoint IDs/names, timestamp, original hop budget, ACK reference and ACK return path are bound. Endpoint keys come from signed profiles or manually verified cards. A signed profile proves control of its keys. Independent fingerprint comparison establishes who controls them.
- **Replay.** The recipient checks endpoint signatures, GCM tags and timestamps before delivery. A durable replay table tracks accepted message IDs per source for their validity period. A duplicate valid DATA packet can elicit another signed ACK without a second history entry or notification. ACK replay cannot complete a second transaction. The endpoint acceptance window is five minutes, with 30 seconds of future-clock tolerance. Keep phone clocks reasonably aligned.
- **Local data.** A schema-versioned SQLite journal stores up to 500 messages with encrypted message records. Indexed endpoint IDs, timestamps, delivery states and replay IDs remain metadata. Saved message content, notification inbox contents and pins use a non-exportable Keystore AES key. Existing local notification inbox records migrate to encrypted storage on access. Historical outgoing records from older memory-only builds cannot be recovered. Pending outgoing records from an interrupted process reopen as unconfirmed failures. App backup is disabled. Notification previews can expose text according to Android and user settings.
- **Limits.** The endpoint envelope is an application protocol built from standard primitives and has not undergone independent cryptographic review. Endpoint encryption uses static recipient wrapping keys and does not provide forward secrecy against later compromise of that key. Trusted relays see routing metadata, can drop traffic, manipulate routes or misreport mutable path data. Device compromise, operator acceptance of a wrong card, traffic analysis, RF interference and denial of service remain outside the protection provided here.

## Background operation

Start from the visible Activity. The connected-device foreground service holds the active networking session in the application repository. Its dedicated ongoing notification opens the app, shows the neighbor count and offers Stop. The separate MeshLink Messages channel only notifies after local destination delivery. Settings provides system/light/dark appearance and notification/lock-screen preview controls.

Home, Recents, screen off and Activity recreation leave ownership with the service. Stop, Bluetooth loss, permission loss or transport failure can end the session. Process death requires a fresh user start. Android can restrict notification visibility, scanning and power use, and manufacturers differ on swipe-away behavior. See [Android background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Device acceptance

Use at least two physical phones for secure direct delivery and three for a relayed message. Check delivery with a locked receiver and a locked relay, rotate the sender, and tap the incoming notification to open its conversation. Repeat after breaking a link and explicitly stopping the service. Emulator tests cannot measure radio range, phone-specific power management, or physical GATT interoperability.

Each message supports up to 2,000 UTF-8 bytes. The journal retains up to 500 records. Upgrading the journal preserves existing history and treats earlier messages as read because older versions did not track unread state.

## Current limits

| Limit | Current behavior |
| --- | --- |
| Direct BLE links | Up to four simultaneous peer sessions per phone; more participants can be reached through relays. |
| Message text | Up to 2,000 UTF-8 bytes, not necessarily 2,000 characters. |
| Group size | Two to eight members including the creator. |
| Group definitions | Up to 64 per installation. Deleting a chat preserves the definition and exit protections. |
| Group roster | Fixed after creation. Existing members can be added back after eligible exits; adding entirely new members requires a new group. |
| History | Up to 500 journal records. Outgoing group and multi-recipient messages use a record per recipient, so this is not 500 group bubbles. |
| Delivery retry window | Routing/transfer/ACK attempts are bounded, with a two-minute overall pending limit in the routing engine. There is no cloud mailbox or guaranteed later delivery to offline recipients. |
| Everyone | A selection of recently known participants at send time, not a persistent channel or a broadcast to future arrivals. |
| Group deletion | Local only. It does not exit or dissolve the group for others. Exit first to stop receiving messages; new messages can reveal a deleted active group chat again. |
| Re-add restriction | Three exits from the same group prohibit further re-adding on that installation. Deleting its chat does not reset the count. |
| Media | Text messaging only; file attachments, photos, voice/video calling and read receipts are not implemented. A delivery ACK is not proof that a human read the message. |

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No nearby devices | Start the mesh on both phones, enable Bluetooth, grant requested permissions and verify BLE central/peripheral support. On older Android versions, check the system location settings required for BLE scanning. |
| A person is listed but messages fail | A remembered profile or advertisement is not an authenticated live connection. Check Nearby's connection label and the failure reason; start the mesh on the other phone and keep an available path through relays. |
| Connect/reconnect does not complete | Check matching builds, permission/Bluetooth state, available direct-link slots, and any physical test allowlist on both phones. Use the connection notice and diagnostic events to distinguish a failed handshake from discovery. |
| Only some group recipients confirm | Open **Delivery details**. Each recipient has a separate route and ACK; one offline member does not imply every copy failed. Use **Retry failed** when a route becomes available. |
| A blocked person's group message arrives | This is intentional in an existing shared group. Personal messages and invitations remain blocked. Exit the group if you do not want further group messages. |
| A group cannot add someone back | Check the member's exit count, whether they blocked the inviter, and whether the inviter received their latest exit notice. Re-adding needs a fresh invitation for the latest exit and is rejected after exit three. |
| A deleted group returns | Deletion is local history removal, not an exit. A fresh message or permitted re-add can show the group again. |
| No notification | Check MeshLink's preferences, Android notification permission and the MeshLink Messages channel. Lock-screen content depends on preview settings. |
| Messages stop in the background | Confirm the foreground mesh service is running. Bluetooth/permission loss, process termination, force-stop and manufacturer power restrictions can interrupt networking. Restart the mesh from the app. |
| Secure packets are rejected | Check compatible builds and reasonably aligned device clocks. Conflicting identity keys or rejected cards require investigation; do not blindly replace a verified fingerprint. |

## Tests and validation

```sh
# JVM tests, static checks, and installable debug APK
./gradlew testDebugUnitTest lintDebug assembleDebug

# Instrumentation on a connected, isolated Android test device or emulator
./gradlew connectedDebugAndroidTest
```

The latest completed local validation on **2026-11-08** recorded **129 passing JVM tests** and **19 passing Android instrumentation tests**, with the two-device `PeerRecoveryTest` skipped in the ordinary single-device suite. Android's runner reports 20 tests including that opt-in test. The debug build and lint completed with zero lint errors (18 warnings and one informational finding).

Coverage includes encryption/signature tampering, replay handling, Android Keystore/TLS operations, framing/CRC, routing failures, slow-transfer timing, encrypted group delivery over different logical paths, member replies, optional verification, personal blocking versus shared-group permissions, exit/re-add protection, missed rejoin notices, selective chat deletion, unread/history migration, notification navigation, themes and service lifecycle. The controlled routing tests exercise the production routing/encryption code; they do not measure physical radio performance.

`PeerRecoveryTest` is opt-in and requires two isolated installations running the same test APK with matching `runId` values. Install both the app and instrumentation APKs, grant the test phones the required permissions, and run these concurrently in separate terminals (substitute the device serials):

```sh
# Phone A
adb -s DEVICE_A shell am instrument -w -e class com.meshlink.app.PeerRecoveryTest -e peerRole driver -e runId recovery-check com.meshlink.app.test/androidx.test.runner.AndroidJUnitRunner

# Phone B
adb -s DEVICE_B shell am instrument -w -e class com.meshlink.app.PeerRecoveryTest -e peerRole responder -e runId recovery-check com.meshlink.app.test/androidx.test.runner.AndroidJUnitRunner
```

Use disposable test installations: instrumentation creates profiles/groups and tests deletion. The current APK has not completed a comprehensive physical multi-phone acceptance matrix; range, throughput, battery use, device-specific GATT behavior and background restrictions still need measurement. User-reported working behavior is not a substitute for that matrix.

## Repository layout

- `app/src/main/`: Android app, Compose screens/view models, foreground service, BLE transport, routing, notifications and secure storage.
- `app/src/main/java/com/meshlink/app/security/`: identity/profile handling, TLS stream adapter, endpoint envelopes, signed group state, encrypted vault and journal.
- `app/src/test/`: JVM routing, framing, cryptographic and policy regression tests; simulation fixtures are confined to test sources.
- `app/src/androidTest/`: Android Keystore/TLS, repository, lifecycle, messaging, group and deletion UI tests.
- `ios/`: historical architecture and validation documents only; Android is the sole build target.
- `verification/` and earlier `*_REPORT.md` files: historical development evidence; screenshots may show features removed from the current Android UI.

For a useful bug report, include the build/version, Android versions and phone models, Nearby connection labels, the exact message failure reason, whether the problem affects personal or group messages, and relevant diagnostic events. Remove private message contents and personal identifiers before sharing logs.

## Voice recording preview (local only)

Existing direct conversations now include **Record voice preview**. Allow microphone
access when prompted, then use Stop, Play/Pause, or Discard. Tap controls support
accessibility without a hold gesture. Text sending remains unchanged. Voice sending,
mesh transfer, persistent voice history and group voice messaging are not implemented.

Recording uses Android MediaRecorder AAC-LC in MPEG-4 (`.m4a`), mono, 16 kHz,
16 kbps, on the existing API 24+ target. Device encoder support is checked by
preparation and errors are shown rather than falling back to raw audio. Recording
stops around 29.5 seconds, with a platform 30-second guard and a 95 KiB file cap.
Oversized or invalid output is discarded. Actual codec behavior needs device testing.
Files reside only in the private cache; they are not yet encrypted at rest. Drafts
are deleted on cancellation, screen departure, or the next recording after a crash.
They are not message history. The BLE service never opens the microphone.

### Device verification checklist (not yet performed)

- Deny microphone permission: verify explanation and continued text messaging.
- Allow permission: record speech, stop, play, pause/resume, and discard.
- Record a very short clip and near the duration limit; verify errors or playable output.
- Press Home, lock, navigate away, or interrupt with another audio app during recording;
  verify microphone release and draft removal. Repeat during playback.
- Verify light/dark themes and TalkBack controls on a small screen with the keyboard open.
- Run VoiceDraftTest with microphone permission granted on a device with a microphone.
- After transfer implementation: test A–B, A–B–C and A–B–C–D with actual relay topology;
  verify endpoint playback, authenticated final ACK, no relay notifications, background
  reception, relay disconnection/recovery, tamper rejection, and untrusted recipients.
- Measure compressed size, elapsed transfer time, throughput, success rate, memory and
  battery on actual phones; no physical transfer or performance results are claimed.

### Secure voice payload API (no BLE voice transfer yet)

`VoicePayloadService.encrypt` accepts a completed private-cache draft, local and
recipient IDs, conversation ID and validated duration. It requires an existing
verified, unblocked peer pin. `decrypt` checks the intended local ID, the verified
sender signature, every record, and the durable journal replay gate. Both run on
Dispatchers.IO. Callers must use the repository's stable local ID, not an ID supplied
by an inbound packet. Returned files are caller-owned and must be deleted after use.

The version-1 container consists of length-prefixed existing MeshLink E2E envelopes:
first encrypted metadata, then independently encrypted 512-byte audio chunks.
Each envelope uses a fresh AES-256 key and random 96-bit nonce, RSA-OAEP SHA-256
(MGF1 SHA-1 for Android Keystore compatibility) wrapping, and ECDSA SHA-256 signing.
Existing Keystore identity keys and peer pins are reused. Every chunk includes a
SHA-256 digest of all metadata and an authenticated index; packet IDs bind transfer
ID and index, while the existing envelope binds sender, recipient and timestamp.
Hashing is a binding mechanism; signature/GCM verification supplies authentication.

Audio bytes (maximum 95 KiB) differ from encrypted container bytes (bounded at 2 MB).
The 512-byte application chunk is not an ATT fragment. Per-chunk envelopes add
considerable overhead but reuse reviewed primitives and permit exact ciphertext
retries without re-encryption. Future transfer code must stage out-of-order records
by index and reconstruct canonical order before decrypting; BLE frame-size checks,
queues, resume state and final destination ACKs are not implemented here. The
existing five-minute envelope validity window also bounds future resumption.

Decryption releases a plaintext file only after all chunks and the durable replay
check succeed. Failed attempts delete partial output. A crash can leave temporary
files, but successful decryption now atomically stores a Keystore-encrypted receipt with
its replay marker in journal schema 3. Future conversation history must consume
these receipts. The bounded receipt inbox holds at most 64 recordings and rejects
new ones when full; no automatic eviction or receipt-management UI exists yet. Private-cache plaintext drafts
and decrypted output are not encrypted at rest. Codec metadata is validated, but
this layer does not decode media to verify its duration or container contents.

### Milestone 3 transport inspection and current boundary

No voice sender, network receiver, transfer-control packets, or final voice ACK is
connected yet. Voice messages are not deliverable. The implemented prerequisite is
atomic durable receipt storage: the secure payload service commits validated audio
and metadata using the existing Vault and SQLite transaction alongside the replay
marker. `voiceReceipt` recovers these records after restart. A failed insert rolls
back the replay marker, allowing a retry. Existing text history is preserved by the
schema-2-to-3 migration. Plaintext preview files remain temporary and unencrypted.

Actual transport boundaries inspected in production code:

- TLS application packets: 32,000 bytes maximum, length-prefixed; this is distinct
  from the 4,096-byte CRC frame payload limit.
- TLS records are segmented into CRC frames, then ATT fragments. Frame segment size
  is capped at `min(4096, (usableChunkSize - 11) * 24 - 6)`.
- Client requests MTU 512; actual negotiated MTU determines fragment capacity.
- Fragment assembly: four incomplete frames, ten-second expiry, at most 456 fragments.
- Per-peer TLS event channel: 256 entries; received packet rate limit: 64/second.
- GATT operations are serialized and have an eight-second completion timeout.
- AODV: 64 pending messages, cached routes valid for 60 seconds, at most three
  attempts and a 120-second pending-message deadline. Relay duplicate suppression
  allows retransmission after eight seconds. Destination ACKs are sent only after
  the delivery callback succeeds, but these are packet ACKs, not complete-audio ACKs.

Future voice scheduling must reserve capacity for text and distinguish chunk ACKs
from authenticated, durable whole-transfer completion. No physical transfer or
routing-recovery result is claimed by this change.

### Milestone 3A production data path

The prior Milestone 3 boundary above is superseded: `MeshRepository.prepareVoicePayload`
creates an encrypted payload and `sendVoicePayload` starts the connected sender.
`voiceTransferStates` exposes queued/sending/waiting/delivered/failed status. These
are repository APIs; the local preview UI still has no Send button.

VoiceWire version 1 uses START, DATA, END, ACK and ERROR inside the existing signed,
recipient-encrypted application envelope. A 768-byte DATA block carries bytes of
the already-encrypted container (not raw audio); application packets stay within
the existing 2,000-byte plaintext limit and use normal TLS/CRC/ATT fragmentation.
Source and destination are authenticated by the outer MessagePacket envelope.

Outgoing packets pass through the normal repository dispatch and AODV send path.
Endpoint delivery diverts voice packets into an eight-entry queue instead of text
history or notifications. Relays use unchanged AODV forwarding. The receiver stages
at most two 2 MB containers, writes by index, compares duplicates, validates all
parts at END, and calls VoicePayloadService for authentication and atomic receipt
insertion. Only then does it send the separately authenticated final ACK. Packet
ACKs pace the single outstanding send window; they never mark the voice delivered.

One outgoing transfer is permitted, with a four-minute overall deadline and normal
AODV per-packet bounded retries/route rediscovery. Missing parts or verification
failure produce ERROR at END. Unknown/rejected packets can cause timeout. Staging
expires after four minutes and abandoned files are deleted when the coordinator is
created. There is no cross-session resume, group transfer, chat bubble integration,
or incoming voice notification. A four-minute limit may be insufficient for large
recordings over slow multi-hop links; no throughput claims are made.

Automated coordinator tests check bounded protocol encoding and commit-before-final-
ACK ordering. They do not establish physical BLE reliability or exhaustive loss,
route-change, forged-control, and crash behavior. Those acceptance tests remain open.

### Direct-chat voice UI integration

The local-preview-only notes above are superseded: direct conversation previews now
have **Send voice**, connected to repository encryption and the real AODV transfer.
Verified peer trust is required. Outgoing audio is stored in the existing encrypted
history body; received audio enters history in the same transaction as its encrypted
receipt and replay marker. Voice bubbles show play/pause, duration, playback progress,
and actual transfer status. Retry retains the chat message ID and re-encrypts audio
with a fresh validity window. Received-message notifications use the actual END
packet route and existing notification privacy/deduplication policy.

Transfers remain repository-owned after submission. Playback and microphone resources
are screen-owned. Audio playback creates a private temporary file removed on screen
exit. No group voice UI or cross-session transfer resume is provided. Older receipts
created before this history integration are not backfilled into conversations.
Physical recording, delivery, notifications and lifecycle behavior still require
real-device acceptance testing; broad voice-specific fault-injection coverage is
unfinished. The existing 64-receipt capacity remains a limitation.

### Reliability hardening

Redundant receipt rows are now reclaimed once their audio exists in encrypted history;
history and replay markers are not removed by this cleanup. Duplicate lookup falls
back to authenticated history. Up to 64 older receipt-only records are preserved;
the staging-row safety cap is 128 so legacy rows cannot block new history-backed
receipts. The existing 500-message history retention policy remains unchanged.

Fresh retries replace stale staging only for the same authenticated sender and a
newer creation timestamp. Final ACK/ERROR matching now checks endpoint, container
size/count and END phase. VOICE logs include transfer/packet IDs, chunk progress and
terminal reason; existing AODV logs supply route/retry/forwarding evidence. Deterministic
coordinator tests inject I/O dispatcher and clock instead of sleeping in wall time.
See [physical acceptance log](verification/VOICE_PHYSICAL_CHECKLIST.md); all physical
results are explicitly unverified.

### Persistent outgoing voice FIFO

Direct-chat Send now accepts up to **10 pending notes / 950 KiB of compressed audio**,
including the active note. These defaults are enforced transactionally by the journal.
The one-active VoiceTransfer invariant remains unchanged. A single repository consumer
uses the schema-4 SQLite queue sequence as its durable FIFO; a conflated wake channel
reacts to enqueue and Mesh state changes without polling while Mesh is off.

Audio is copied into the existing Vault-encrypted history body in the same transaction
as queue insertion. Recorder files are not retained by the queue. Network encryption
is deferred until dispatch, with a fresh validity timestamp and the stable chat ID.
Rapid repeat submission of the same draft is ignored within the repository session.
Queue-full errors preserve the draft. Pending rows are protected from history pruning.

Mesh OFF leaves queued work Waiting for mesh. Restart restores queue order; interrupted
work is retried with fresh encryption when the user starts Mesh. No foreground service
is started automatically. Destination receipts and replay checks still prevent duplicate
message delivery. A terminal failure while Mesh remains active advances to the next
note. Manual Retry appends the same chat ID to the tail. Cancel send removes pending
work; active cancellation releases the coordinator slot. Audio remains in encrypted
history for playback under existing retention rules.

Physical retest: submit A/B/C while A sends; verify only A has chunk progress, B/C say
Queued, then start in order. Repeat with A failing, Mesh OFF/ON, process restart while
sending, cancel B, Retry A (tail), and queue capacity rejection. Send text during each
run. All new persistence/device tests require Android instrumentation; no physical FIFO
result has been verified in this development session.

## Text-first input

MeshLink focuses on encrypted text messaging over BLE with AODV routing and delivery acknowledgments. `OfflineSpeechInput.kt` provides offline speech-to-text input where supported by the device. Microphone permission remains required for speech input. Legacy voice-note code is retired but retained where it overlaps existing uncommitted work or historical journal compatibility; see [cleanup audit](verification/CLEANUP_AUDIT.md).
