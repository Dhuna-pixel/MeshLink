# Shared transport retest — results pending

Use the new debug APK on both phones. Keep recording/codec settings unchanged.
Capture each phone's MeshLink logs before toggling Mesh: restart discards evidence.
No physical test in this document has been performed by the agent.

1. Start Mesh, verify identities, connect, send text both ways.
2. Send a 3–5-second voice A→B, then text both ways, then another voice B→A.
   Repeat ten times without toggling Mesh. Expect destination ACKs and no retained slot.
3. During a voice transfer disconnect B. After failure, reconnect without stopping Mesh.
   Send text first, then Retry the same voice. Expect route rediscovery and final ACK.
4. If progress stalls, immediately capture both logs and connected-neighbor/route views.
   Try one text message before restart. Record timestamp, packet/transfer IDs and chunk.
5. Stop/start Mesh five times, including once mid-transfer. Expect the old transfer to
   fail and release its slot; a new text and voice must work in the new session.
6. Repeat through the physical A–B–C allowlist, interrupting B–C. Do not infer relay
   behavior from the known-device list; capture actual route and forwarding logs.

Diagnostic interpretation:
- BLE-WRITE start without release: outstanding operation; capture whether timeout follows.
- BLE-WRITE release completed=false plus disconnect: timed-out operation, session reset.
- BLE-QUEUE worker_inactive: stale worker detected and torn down.
- BLE-QUEUE backpressure: queue admission refused, not destination delivery.
- AODV control=RREQ accepted=false: discovery not admitted to transport.
- RREQ accepted=true but no rrep_received: discovery/reply path loss.
- rrep_received with rrep_route usable=false: reply did not establish a usable route.
- dispatch accepted=true then awaitingTransfer=true at exhaustion: wire completion missing.
- awaitingTransfer=false at exhaustion: wire completed but destination ACK missing.
- VOICE slot=released: outgoing ownership cleanup completed.

Save actual outcomes, phone models/OS, MTUs, elapsed times, transfer IDs, and all-node
logs. Logs contain identifiers and operational metadata only; do not include audio,
plaintext message bodies, keys, or TLS secrets.

Code verification is not a physical root-cause confirmation. Remaining acceptance
coverage includes Android callback loss, simultaneous bidirectional traffic, and
control latency under sustained multi-peer load.

## Concurrent-send follow-up

Policy: one outgoing voice transfer. While A sends, record B and tap Send. Expect
"A voice message is already sending. Wait for it to finish, then tap Send again."
B must stay in preview, with no failed chat entry and no second encryption allocation.
A must continue unaffected. After A finishes/fails, tap B's Send again. This is clean
rejection, not FIFO queuing; delayed encrypted payloads have a five-minute validity.

Send text both directions while A transfers and immediately after its failure. If text
fails, capture both phones' Logcat (MeshLink transport tags) before restarting Mesh.
Diagnostics now have their own lossy stream; packets/disconnects use a separate bounded
queue. A critical queue overflow explicitly stops Mesh rather than silently losing
routing state. Record whether such an overflow occurred. Authenticated receiver ERROR
packets now report START/DATA rejection without waiting for END.

Code evidence: the original generic exception was the compound require in
VoiceTransfer.send at pre-change line 74 (outgoing.size < 1). The shared-flow overflow
path previously dropped packet/disconnect events alongside high-volume logs. No logs
from the physical failing run were available, so neither overflow nor another specific
transport condition has been established as the cause of the observed stall.

## Sustained A/B/C follow-up (unverified)

No both-phone captures were present in supplied attachments or verification files.
Earliest available evidence is the reported 35/41 stall and subsequent bilateral
text failure; the responsible subsystem and first failing event are unconfirmed.

New local checks: READY requires a live TLS worker. The watchdog tears down a dead
READY worker or a write completion overdue by more than 12 seconds (the actual write
await still times out at 8 seconds). The repository reconciles connected neighbors
against transport readiness each second, so stale route membership does not depend
only on disconnect-event arrival. Reconnection still uses existing scan/identity/
backoff rules. Pending FIFO work waits when no ready neighbor exists, rather than
exhausting every queued message while a link is recovering. No automatic app/service
restart or fabricated ACK is used.

Capture BOTH phones from before A starts until at least 60 seconds after bilateral
text failure, before toggling Mesh. Supply full timestamped Logcat, phone models/OS,
local node IDs, negotiated MTUs, which phone is BLE central/peripheral, and the
transfer IDs and approximate failure time. Include MeshLink BLE-WRITE, BLE-QUEUE,
BLE-WORKER, BLE-HEALTH, MESH-HEALTH, AODV, VOICE, critical_overflow, and Android Bluetooth
stack entries. UI network logs retain only a bounded tail; screenshots alone cannot
establish first failure. Retain packet IDs to correlate START/DATA/END, RREQ/RREP and
ACKs. Do not include plaintext audio, message bodies, keys or session secrets.

Retest: queue A/B/C; send texts both ways during and after C; induce a brief relay/link
disconnect; verify Waiting for mesh preserves FIFO and reconnect resumes work. Capture
whether BLE-HEALTH fires, whether neighbor_down follows, and whether a fresh RREQ/RREP
and final authenticated ACK occur without manual restart. All actual results: NOT RUN.

## Stream integrity and fair scheduling correction

Confirmed code mechanism: FragmentAssembler previously expired incomplete frames after
10 seconds without notifying the BLE/TLS session; handleIncomingRawChunk also swallowed
fragment exceptions. Since these frames carry portions of an ordered TLS byte stream,
discarding one cannot be repaired by a later application retransmission. A live worker
could remain READY while its TLS stream had lost bytes. Expiration/invalid fragments now
tear down that specific session, allowing existing authenticated reconnection to create
a fresh stream. Timeouts and crypto verification are unchanged.

The former shared FIFO reserved admission space but did not prioritize processing.
FairMailbox now serves incoming TLS / routing-or-packet-ACK / incoming TLS / application
/ incoming TLS / routing-or-packet-ACK in bounded turns, skipping empty lanes. Each lane
retains FIFO order and capacities total 256 (232/16/8). SSLEngine operations remain serial;
a running packet write is not preempted, but queued bulk traffic cannot indefinitely
postpone reads or routing ACKs. Application text and encrypted voice share the bounded
eight-entry application lane; the voice sender still has one outstanding packet.

Tests exercise production fragment reassembly, mailbox scheduling and GATT callback
await boundaries. Five mailbox batches with immediate text are not a full five-voice
cryptographic/BLE integration test. Physical acceptance of the reported Samsung failure
and the complete fault matrix remain unverified. No physical root cause is claimed.

The Log screen now offers Copy transport diagnostics (up to 100 matching entries from
the existing bounded log). This avoids requiring USB collection, although rapid traffic
can overwrite older evidence. It excludes normal message-body log categories.

## Correlated NODE-A/NODE-B worker-cancellation evidence

Observed A: final DATA write completion at 1794304023307; worker canceled at
1794304023583; neighbor down/RERR at 1794304023593. B continued local ANNOUNCE writes.
This proves A's worker stopped and its routing neighbor was removed. It does not
identify who initiated close or establish successful TLS reception on A afterward.

Cancellation origins audited: mesh stop; local disconnect (including fragment expiry,
protocol failure, write failure, watchdog); remote GATT disconnect callback; replacing
an unsubscribed ACL session; CCCD subscription replacement; mailbox closure; parent/job
cancellation. The transport parent SupervisorJob has no explicit cancellation site in
this transport. TlsPipe does not independently cancel its worker. Closure now preserves
the first initiating reason and worker exit includes it; remote status and replacement
old/new creation timestamps are included. Copy transport diagnostics includes these.

Confirmed premature-expiry mechanism: eight valid fragments spaced 2 seconds apart
failed the previous ten-second TOTAL lifetime. Expiry now measures ten seconds since
the last NEW fragment, with an absolute sixty-second cap. Repeats cannot refresh the
idle deadline. Frame loss still closes the TLS stream, never skips TLS bytes.

Confirmed asymmetric-health gap: successful outbound writes previously allowed a
READY peer with no incoming authenticated traffic to persist. With existing ANNOUNCE
traffic every 30 seconds, over 100 seconds without validated inbound traffic now
closes that session, followed by normal bounded discovery/reconnection. This is a
missing-heartbeat health threshold, not an extended write/ACK timeout. Local disconnect
requests both client and server ACL teardown. Incoming callbacks for replaced sessions
are rejected before touching their assembler or closing a replacement.

Tests cover fragment progress/expiry and asymmetric health decision boundaries. They
do NOT emulate Android's complete authenticated two-device reconnect lifecycle; that
requested integration test and physical recovery validation remain outstanding.
