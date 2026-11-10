# Android architecture

The single Gradle module `:app` uses Kotlin and Jetpack Compose. MeshViewModel and MeshRepository connect UI state with transport, identity, routing, and persistence.

AndroidBleGattTransport handles BLE connections and TLS-backed sessions. Framing splits traffic into BLE-sized fragments. AodvEngine handles route discovery and forwarding; endpoint acknowledgments establish delivery. Transport completion alone is insufficient. EndToEnd seals text envelopes independently of hop security; MessageJournal persists local history.

OfflineSpeechInput owns on-device recognition sessions and lifecycle cleanup. SpeechDraft rejects stale callbacks; SpeechLevel consumes RMS samples and SpeechWaveform renders the level. Speech recognition supplies editable text, not an audio attachment.

Retired voice transfer classes remain because they are existing untracked work referenced by modified source and tests. Journal tables, receipt handling, and serialized voice metadata also protect historical records. Do not delete these piecemeal or alter schemas without a tested migration. Their presence does not mean voice notes are supported.

Legacy Apple sources and build configurations are deleted in the pending working-tree changes. Historical iOS Markdown and cross-platform packet fixtures remain as reference and serialization regression coverage.
