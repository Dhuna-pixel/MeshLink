# Publication preparation — 2026-11-10

## Scope and outcome

Prepared locally on main. No staging, commits, push, history rewriting, or remote settings changes. GitHub itself has not been updated; remote branch divergence and website settings were not inspected. No further files deleted in this pass. The previous 59 Apple-source/build deletions remain unstaged and will be represented by deletion entries when explicitly staged and committed (312,960 source bytes removed).

README now covers the Android-only architecture, multi-hop delivery, security, strictly on-device speech, RMS limitations, real existing screenshots, SDK requirements, permissions, two-device instructions, limitations and future work. No license section was added because no license file exists. The prior README is preserved in docs/PREVIOUS_README.md. Current architecture and development instructions were added. Signing-key ignore patterns were added without deleting files.

## Preserved work and remaining legacy

All pre-existing files except the explicitly refreshed README and .gitignore are byte-for-byte unchanged in this pass. The prior README is preserved in full beneath an archival notice. No Android source, manifest, Gradle dependencies, tests, or screenshots were edited. Shared BLE/TLS, AODV, encryption, fragmentation, acknowledgments, journal and speech code are unchanged.

Retired audio, voice transfer/UI/security helpers and voice tests remain existing user work. Some are still compile-time dependencies of modified repository and historical journal code. Do not omit referenced untracked Kotlin files from a feature commit simply because voice notes are retired. Removing the full implementation needs permission to revise that existing work and a coordinated compatibility refactor. No dependencies were removed. Historical iOS Markdown, packet compatibility tests and verification screenshots remain useful evidence.

## Automation and validation

No .github directory or Actions workflow exists locally; none added. Local validation is documented without assuming a hosted offline cache.

Requested offline testDebugUnitTest, lintDebug, assembleDebug and compileDebugAndroidTestKotlin command: BUILD SUCCESSFUL, 57 tasks, 2 executed and 55 up-to-date. Initial restricted attempt failed on the Gradle lock; authorized retry succeeded. Instrumentation tests compiled, not executed. Real device recognizer, waveform and radio behavior were not newly tested. git diff --check passed. Current README relative links resolve; archival documents intentionally retain historical source references.

Cached unit XML: 180 tests; 0 failures/errors.

- com.meshlink.app.ui.components.SpeechLevelTest: 5 tests, 0 failures, 0 errors.
- com.meshlink.app.ConversationsTest: 3 tests, 0 failures, 0 errors.
- com.meshlink.app.ui.components.SpeechDraftTest: 7 tests, 0 failures, 0 errors.

## Hygiene concerns

A filename and common private-key/token-pattern scan of current tracked and nonignored untracked files found no matching secrets, signing stores, local.properties, APKs or AABs. This is not a full secret-history audit. Ignored files were not proposed for publication or removed. Personal machine paths remain in historical records listed below; review or redact those paths before public publication if desired, preserving the original records locally. No secret values are reproduced here.

- `verification/CLEANUP_AUDIT.md`
- `DYNAMIC_BACKGROUND_REPORT.md`
- `IMPLEMENTATION_REPORT.md`
- `NOTIFICATIONS_THEME_REPORT.md`
- `UX_SIMULATION_REPORT.md`

## Exact proposed commit inventory

Review the existing feature changes along with this refresh. The following inventory includes existing work, not just changes authored in this pass. Stage explicit paths after approval, including deletions; ignored outputs are excluded. The feature additions are needed to keep the current source and tests self-contained. Historical verification assets are optional publication evidence and should receive a visual/privacy review.

Suggested commits:

1. `Update Android messaging UI, transport reliability and offline speech input` — reviewed existing Android modifications and all required source/test additions.
2. `Remove obsolete Apple builds and refresh Android documentation` — deletion inventory, documentation, ignore changes, and approved verification evidence.

### Modified tracked files

- `.gitignore`
- `README.md`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/meshlink/app/MainActivity.kt`
- `app/src/main/java/com/meshlink/app/data/repository/MeshRepository.kt`
- `app/src/main/java/com/meshlink/app/data/transport/AndroidBleGattTransport.kt`
- `app/src/main/java/com/meshlink/app/data/transport/framing/BleFragments.kt`
- `app/src/main/java/com/meshlink/app/domain/models/MessagePacket.kt`
- `app/src/main/java/com/meshlink/app/domain/routing/AodvEngine.kt`
- `app/src/main/java/com/meshlink/app/security/EndToEnd.kt`
- `app/src/main/java/com/meshlink/app/security/MessageJournal.kt`
- `app/src/main/java/com/meshlink/app/ui/components/ParticipantName.kt`
- `app/src/main/java/com/meshlink/app/ui/components/StatusBadges.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/GroupScreen.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/LogScreen.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/MainScreen.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/MessagingScreen.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/NeighborsScreen.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/SettingsScreen.kt`
- `app/src/main/java/com/meshlink/app/ui/theme/Theme.kt`
- `app/src/main/java/com/meshlink/app/ui/theme/Type.kt`
- `app/src/test/java/com/meshlink/app/data/transport/framing/BleFragmentsTest.kt`
- `app/src/test/java/com/meshlink/app/domain/routing/AodvEngineTest.kt`
- `app/src/test/java/com/meshlink/app/security/EndToEndTest.kt`
- `ios/CROSS_PLATFORM_COMPATIBILITY.md`
- `ios/IOS_MESHLINK_ARCHITECTURE.md`
- `ios/README.md`
- `ios/TEST_RESULTS.md`

### Deleted tracked files

- `Package.swift`
- `ios/MeshLink.xcodeproj/project.pbxproj`
- `ios/MeshLink.xcodeproj/project.xcworkspace/contents.xcworkspacedata`
- `ios/MeshLink.xcodeproj/xcshareddata/xcschemes/MeshLink.xcscheme`
- `ios/MeshLink/App/Info.plist`
- `ios/MeshLink/App/MeshLinkApp.swift`
- `ios/MeshLink/Data/Repository/MeshRepository.swift`
- `ios/MeshLink/Data/Serialization/PacketSerializer.swift`
- `ios/MeshLink/Data/Transport/CoreBluetoothTransport.swift`
- `ios/MeshLink/Data/Transport/MeshTransport.swift`
- `ios/MeshLink/Data/Transport/MultipeerMeshTransport.swift`
- `ios/MeshLink/Data/Transport/SimulationTransport.swift`
- `ios/MeshLink/Domain/Models/ForwardingDecision.swift`
- `ios/MeshLink/Domain/Models/MessagePacket.swift`
- `ios/MeshLink/Domain/Models/NetworkLogEntry.swift`
- `ios/MeshLink/Domain/Models/Node.swift`
- `ios/MeshLink/Domain/Routing/RoutingEngine.swift`
- `ios/MeshLink/Domain/Routing/SeenMessageManager.swift`
- `ios/MeshLink/Presentation/Components/BluetoothPermissionBanner.swift`
- `ios/MeshLink/Presentation/Components/LogLevelBadge.swift`
- `ios/MeshLink/Presentation/Components/MetricCard.swift`
- `ios/MeshLink/Presentation/Components/NodeIdentitySheet.swift`
- `ios/MeshLink/Presentation/Components/NodeStatusBadge.swift`
- `ios/MeshLink/Presentation/Components/PacketInspectorSheet.swift`
- `ios/MeshLink/Presentation/Screens/LogScreen.swift`
- `ios/MeshLink/Presentation/Screens/MainTabView.swift`
- `ios/MeshLink/Presentation/Screens/MessagingScreen.swift`
- `ios/MeshLink/Presentation/Screens/NeighborsScreen.swift`
- `ios/MeshLink/Presentation/Screens/SimulationScreen.swift`
- `ios/MeshLink/Presentation/Screens/StatusScreen.swift`
- `ios/MeshLink/Presentation/Theme/MeshColors.swift`
- `ios/MeshLink/Presentation/ViewModels/MeshUiState.swift`
- `ios/MeshLink/Presentation/ViewModels/MeshViewModel.swift`
- `ios/MeshLink/Resources/Assets.xcassets/AccentColor.colorset/Contents.json`
- `ios/MeshLink/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json`
- `ios/MeshLink/Resources/Assets.xcassets/Contents.json`
- `ios/MeshLink/Utilities/IdGenerator.swift`
- `ios/MeshLinkCore/Models/ForwardingDecision.swift`
- `ios/MeshLinkCore/Models/MessagePacket.swift`
- `ios/MeshLinkCore/Models/NetworkLogEntry.swift`
- `ios/MeshLinkCore/Models/Node.swift`
- `ios/MeshLinkCore/Repository/MeshRepository.swift`
- `ios/MeshLinkCore/Routing/RoutingEngine.swift`
- `ios/MeshLinkCore/Routing/SeenMessageManager.swift`
- `ios/MeshLinkCore/Serialization/PacketSerializer.swift`
- `ios/MeshLinkCore/Transport/CoreBluetoothTransport.swift`
- `ios/MeshLinkCore/Transport/MeshTransport.swift`
- `ios/MeshLinkCore/Transport/MultipeerMeshTransport.swift`
- `ios/MeshLinkCore/Transport/SimulationTransport.swift`
- `ios/MeshLinkCore/Utilities/IdGenerator.swift`
- `ios/MeshLinkCoreTests/CrossPlatformCompatibilityTests.swift`
- `ios/MeshLinkCoreTests/MessagePacketTests.swift`
- `ios/MeshLinkCoreTests/RoutingEngineTests.swift`
- `ios/MeshLinkCoreTests/SeenMessageManagerTests.swift`
- `ios/MeshLinkTests/CrossPlatformCompatibilityTests.swift`
- `ios/MeshLinkTests/MessagePacketTests.swift`
- `ios/MeshLinkTests/RoutingEngineTests.swift`
- `ios/MeshLinkTests/SeenMessageManagerTests.swift`
- `ios/MeshLinkTests/run_tests.swift`

### New files proposed for review

- `app/src/androidTest/java/com/meshlink/app/RedesignLayoutTest.kt`
- `app/src/androidTest/java/com/meshlink/app/VoiceDraftTest.kt`
- `app/src/androidTest/java/com/meshlink/app/VoiceQueueTest.kt`
- `app/src/androidTest/java/com/meshlink/app/VoiceReceiptTest.kt`
- `app/src/main/java/com/meshlink/app/audio/RecordingState.kt`
- `app/src/main/java/com/meshlink/app/audio/VoiceDraft.kt`
- `app/src/main/java/com/meshlink/app/data/transport/BleQueueBudget.kt`
- `app/src/main/java/com/meshlink/app/data/transport/BleSessionHealth.kt`
- `app/src/main/java/com/meshlink/app/data/transport/FairMailbox.kt`
- `app/src/main/java/com/meshlink/app/data/transport/GattWriteAwait.kt`
- `app/src/main/java/com/meshlink/app/domain/voice/VoiceManifest.kt`
- `app/src/main/java/com/meshlink/app/domain/voice/VoiceTransfer.kt`
- `app/src/main/java/com/meshlink/app/security/SecureVoicePayload.kt`
- `app/src/main/java/com/meshlink/app/security/VoicePayloadService.kt`
- `app/src/main/java/com/meshlink/app/ui/components/MeshComponents.kt`
- `app/src/main/java/com/meshlink/app/ui/components/OfflineSpeechInput.kt`
- `app/src/main/java/com/meshlink/app/ui/components/SpeechLevel.kt`
- `app/src/main/java/com/meshlink/app/ui/components/SpeechWaveform.kt`
- `app/src/main/java/com/meshlink/app/ui/components/VoiceBubble.kt`
- `app/src/main/java/com/meshlink/app/ui/components/VoiceDraftControls.kt`
- `app/src/main/java/com/meshlink/app/ui/theme/Dimensions.kt`
- `app/src/test/java/com/meshlink/app/audio/RecordingStateTest.kt`
- `app/src/test/java/com/meshlink/app/data/transport/BleQueueBudgetTest.kt`
- `app/src/test/java/com/meshlink/app/data/transport/BleSessionHealthTest.kt`
- `app/src/test/java/com/meshlink/app/data/transport/FairMailboxTest.kt`
- `app/src/test/java/com/meshlink/app/data/transport/GattWriteAwaitTest.kt`
- `app/src/test/java/com/meshlink/app/domain/voice/VoiceManifestTest.kt`
- `app/src/test/java/com/meshlink/app/domain/voice/VoiceTransferTest.kt`
- `app/src/test/java/com/meshlink/app/security/SecureVoicePayloadTest.kt`
- `app/src/test/java/com/meshlink/app/ui/components/SpeechDraftTest.kt`
- `app/src/test/java/com/meshlink/app/ui/components/SpeechLevelTest.kt`
- `docs/ARCHITECTURE.md`
- `docs/DEVELOPMENT.md`
- `docs/PREVIOUS_README.md`
- `verification/CLEANUP_AUDIT.md`
- `verification/OFFLINE_SPEECH.md`
- `verification/RELEASE_PREPARATION.md`
- `verification/SHARED_TRANSPORT_RETEST.md`
- `verification/SPEECH_DISCONNECT.md`
- `verification/VOICE_PHYSICAL_CHECKLIST.md`
- `verification/ui-review/phone/phone-dark-chats.png`
- `verification/ui-review/phone/phone-dark-conversation.png`
- `verification/ui-review/phone/phone-dark-events.png`
- `verification/ui-review/phone/phone-dark-nearby.png`
- `verification/ui-review/phone/phone-dark-settings.png`
- `verification/ui-review/phone/phone-keyboard.png`
- `verification/ui-review/phone/phone-light-chats.png`
- `verification/ui-review/phone/phone-light-conversation.png`
- `verification/ui-review/phone/phone-light-events.png`
- `verification/ui-review/phone/phone-light-nearby.png`
- `verification/ui-review/phone/phone-light-settings.png`

## Manual GitHub settings

No mandatory website change was established. After approval and publication, consider an About description such as “Android BLE mesh messenger with encrypted text and on-device speech input” and relevant Android/Kotlin/BLE topics. Decide on a license before claiming open-source licensing. Review branch protection and release/signing policy separately if wanted. No badges, releases, download links, visibility changes or website settings were created.
