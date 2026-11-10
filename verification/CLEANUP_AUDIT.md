# Conservative cleanup audit — 2026-11-10

Branch: main. No commit, push, reset or clean performed.

## Classification and actions

A — Removed: unchanged tracked Apple-only Swift implementation, Swift tests and runner, Xcode configuration, asset catalogs and root Package.swift. Gradle includes only :app and has no references to these files. 59 files, 312,960 bytes (about 306 KiB) removed from the working tree; Git history is unchanged. No entire ios directory deletion: historical Markdown records remain.

B — Retained pending explicit permission to modify existing work: audio/, domain/voice/, SecureVoicePayload.kt, VoicePayloadService.kt, VoiceBubble.kt, VoiceDraftControls.kt and associated voice tests are pre-existing untracked files. MeshRepository.kt already contains modified legacy transfer, queue and cancellation code. Removing these would conflict with the instruction to preserve ALL existing uncommitted and untracked work. A future coordinated refactor should remove transfer workers and UI while preserving historical data and protocol rejection behavior.

C — Preserved for compatibility: MessagePacket voice fields, EndToEnd validation, MessageJournal schemas and receipts, journal migration tests, CrossPlatformPacketCompatibilityTest and shared transport tests. Microphone permission and hardware declaration support OfflineSpeechInput.kt. All Android source, resources, manifest, Gradle dependencies and tests remain byte-for-byte unchanged by this cleanup. Dependencies removed: none; no dedicated third-party audio dependency is declared.

D — Retained: ignored app/build and .gradle contents, local configuration, logs, LOCAL_GIT_STATUS.txt, verification images and reports, and historical iOS Markdown. Build caches support offline validation; logs and snapshots may be useful debugging records. No files were removed solely from a name or ignore match. No confirmed redundant Android resources were removed.

README now describes the Android-only build and offline speech input; its original edits were retained. Historical iOS documents have a notice explaining that referenced source paths and commands are archival. Ignore patterns were added for APK/AAB outputs and Kotlin/native build caches without deleting matching user files.

## Validation

Requested command: `ANDROID_HOME=/path/to/Android/sdk ./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --offline`.

BUILD SUCCESSFUL; 57 tasks, 2 executed and 55 up-to-date. Gradle reused existing task outputs. Instrumentation tests were compiled, not run on a device. Initial restricted attempt could not open the Gradle cache lock; the authorized retry succeeded. `git diff --check` passed.

Existing unit-test XML results: 175 tests, 0 failures/errors. These are cached results accepted by Gradle, not a forced fresh test run.

- com.meshlink.app.ConversationsTest: 3 tests, 0 failures/errors.
- com.meshlink.app.ui.components.SpeechDraftTest: 7 tests, 0 failures/errors.

SHA-256 verification confirms every original nonignored untracked file and every originally modified file except the intentionally updated README is unchanged. Shared BLE/TLS, fragmentation, AODV, security, service, journal and speech-input logic were not edited. Original README content was changed only for current platform status and the appended input explanation.

## Deleted file inventory

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
