# Offline speech replacement

Changed MessagingScreen.kt, MeshComponents.kt, MeshRepository.kt; added OfflineSpeechInput.kt and SpeechDraftTest.kt.

Historical voice metadata, audio, receipt records, journal schema and queued records are retained. Voice transfer classes, payload encryption, recorder/player components and old instrumentation tests remain as legacy code to preserve existing untracked work. Production conversation UI no longer invokes them. Repository queue collectors are removed, incoming VoiceWire packets are discarded before transfer creation, send entry points reject new voice work, retries ignore voice records, and shutdown no longer instantiates the lazy transfer. Shared BLE, TLS, fragmentation, routing, journal and text acknowledgments remain intact.

Recognition is restricted to createOnDeviceSpeechRecognizer on API 31+, with availability checked before creation. No default/cloud recognizer is created. Permission is requested only by microphone activation. Missing language models report unavailability; no download is initiated. Recognized text appends to the current draft and never invokes send. Lifecycle stop/disposal cancels recognition; stale callbacks are ignored.

Physical validation required: API 24–30 unavailable state; API 31+ with and without an installed recognizer/model; airplane-mode dictation; permission denial/revocation; cancel and background during listening/transcribing; repeated sessions and navigation; tablet and narrow-phone layouts; encrypted text delivery across multiple BLE hops while dictating. Existing voice instrumentation tests describe retired behavior and should not be used as acceptance tests for this UI. Instrumentation compilation does not execute device tests.
