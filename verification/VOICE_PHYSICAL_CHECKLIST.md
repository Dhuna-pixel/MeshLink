# Voice physical acceptance log

Status: NOT RUN. No real-device result is implied by the build or JVM tests.

Record for every run: APK version/build hash, date, phone models and Android versions,
node IDs, trust state, allowed neighbors, negotiated MTUs, battery/power restrictions,
recording duration, encoded bytes, elapsed send time, transfer ID, final outcome.
Start with 3–5 seconds of speech. Keep all phones' networking foreground services on.
Pair/verify A and the final destination; authenticate adjacent BLE links. Disable Wi-Fi
and mobile data; do not use the simulator. Preserve each phone's network log.

For forced paths enable the existing **physical BLE demo allowlist**, configuring only
adjacent neighbors on every device. Confirm the live connected-neighbor list before
sending; merely placing phones apart does not prove multi-hop delivery.

| Test | Procedure | Expected | Actual / diagnostics |
|---|---|---|---|
| A: A–B | Allow A↔B only. Record, preview, send; play on B. | One incoming bubble, playable speech; A Delivered only after B commit/final ACK. | NOT RUN |
| B: A–B–C | A allows B; B allows A,C; C allows B. Verify A has no C link. Send A→C. | B logs forwarding; C plays audio; B gets no message/notification. | NOT RUN |
| C: A–B–C–D | Allow only adjacent edges bidirectionally. Send A→D. | B,C forward; D stores/plays; no shortcut links; A gets final ACK. | NOT RUN |
| D: Locked receiver | Lock B (then repeat with C/D). Send with receiver service running. | Receipt/notification when Android permits; unlock opens correct chat. | NOT RUN |
| E: Relay loss | During B/C, disconnect middle relay; reconnect before deadline. Repeat without reconnecting. | Bounded route rediscovery; delivery or explicit failure, no false Delivered. | NOT RUN |
| F: Retry | After failure restore links, tap Retry once; repeat rapid taps. | Same outgoing chat ID; no duplicate incoming bubble; fresh secure attempt succeeds or fails clearly. | NOT RUN |
| G: Restart | Restart both apps after receipt. Open chat and play. Also restart during transfer. | Completed audio persists; interrupted transfer does not claim delivery/resumption. | NOT RUN |
| H: Tampering | On a controlled test build/device harness alter signed DATA/ACK/ERROR bytes; also inject wrong-source control and remove a DATA packet. | Signature/authentication rejection or bounded failure; no playable corrupt bubble, no forged Delivered. | NOT RUN—requires instrumented injection harness |

Collect VOICE transfer/packet IDs, index/count, terminal reason; correlate packet IDs
with AODV discovery/retry/RERR/forwarding and TX/ACK records on all devices. Capture
actual endpoint hop/route evidence. Never record audio bytes, keys, or message content.

Also check: microphone denial; immediate Stop; duration limit; two rapid Send taps;
Home/navigation during encryption, transfer, and playback; another audio app/call;
corrupt/missing local audio; notification privacy and duplicate suppression.
Do not mark a check passed from visual sender progress alone. For every failure attach
all-node logs, reproducible steps, expected result, actual result, and elapsed time.
