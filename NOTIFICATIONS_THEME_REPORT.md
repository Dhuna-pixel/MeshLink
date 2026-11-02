# Features 5 and 6 — local implementation report

Date: 2026-11-02. Changes remain local and uncommitted. Existing pending work was preserved; no Git history operations were performed.

## Delivered behavior

- BLE application-message notifications are attached only to the production AODV final-delivery callback, after framing/reassembly, CRC validation, decoding, TTL/routing acceptance and engine deduplication. Intermediate relays, control traffic, acknowledgments and virtual simulator messages do not notify.
- Dedicated **MeshLink Messages** channel, normal importance, monochrome icon, friendly sender with sanitized short-ID fallback, bounded preview and expanded text. Android settings and DND retain control.
- Immutable, individually identified PendingIntents reuse the existing activity and open Messages plus the relevant packet inspector. A bounded persisted received inbox supports taps after process death. If an old message has been evicted, Messages opens with an explanation.
- Android 13+ permission is requested once when starting BLE networking or through Settings. Denial does not prevent delivery. Settings exposes system permission/channel status, notification enablement and lock-screen preview privacy. Existing notifications are cleared when disabling notifications or changing preview privacy.
- Light, Dark and System Default are persistent and immediately applied through the root Material 3 theme. System Default is the initial preference. SharedPreferences follows the project's existing storage architecture; initial preferences load before UI composition.
- Theme-aware screen, dialog, dropdown, card, status and simulator colors. System bars follow the effective theme. Simulator source/destination/offline labels, route thickness and disabled-link dashes supplement color.
- No background BLE service added. Networking remains foreground-only; closing/stopping the app stops networking.

## Validation

- **99 JVM unit tests passed**, zero failures/errors/skips, including 21 added notification/preferences/theme-contrast tests and the existing 78 regressions.
- **3 Android instrumentation tests passed** on MeshLink_Test_Phone, Android 17/API 37.1: all three appearance selections through main navigation, simulator scenario menu and identity dialog; notification-target validation; real Android notification posting with private/public presentation assertions, immutable PendingIntent, and tap navigation to Messages.
- Light/dark text-role contrast test checks at least 4.5:1 for the tested text/background pairs.
- Light and dark Settings and simulator screenshots were captured and visually inspected in `verification/2026-11-02/appearance/`.
- Android lint: **0 errors, 18 warnings, 1 informational finding**. See `app/build/reports/lint-results-debug.html`.
- Debug APK built successfully: `app/build/outputs/apk/debug/app-debug.apk`.
- Commands: `ANDROID_HOME=/Users/Dhuna/Library/Android/sdk ./gradlew connectedDebugAndroidTest testDebugUnitTest lintDebug assembleDebug --offline` (final contrast test rerun with unit/lint/build tasks).
- Android test-only libraries updated to JUnit extension 1.3.0 and Espresso 3.7.0 to resolve the emulator's removed InputManager API. Runtime Android SDK/minimum support was unchanged. Reference: https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0

## Files added for these features

- `app/src/main/java/com/meshlink/app/data/preferences/AppPreferences.kt`
- `app/src/main/java/com/meshlink/app/data/preferences/AndroidPreferenceStorage.kt`
- `app/src/main/java/com/meshlink/app/domain/notifications/DeliveredMessageNotifications.kt`
- `app/src/main/java/com/meshlink/app/data/notifications/AndroidMessageNotifier.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/SettingsScreen.kt`
- `app/src/main/res/drawable/ic_message_notification.xml`
- `app/src/test/java/com/meshlink/app/domain/notifications/MessageNotificationsTest.kt`
- `app/src/test/java/com/meshlink/app/ui/theme/ThemeContrastTest.kt`
- `app/src/androidTest/java/com/meshlink/app/AppearanceTest.kt`

## Existing files updated for these features

- `app/build.gradle.kts` (test-only compatibility dependencies)
- `app/src/main/AndroidManifest.xml` (singleTop activity)
- `app/src/main/java/com/meshlink/app/MainActivity.kt`
- `app/src/main/java/com/meshlink/app/data/repository/MeshRepository.kt`
- `app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshUiState.kt`
- `app/src/main/java/com/meshlink/app/presentation/viewmodels/MeshViewModel.kt`
- `app/src/main/java/com/meshlink/app/ui/theme/Color.kt` and `Theme.kt`
- `app/src/main/java/com/meshlink/app/ui/screens/`: MainScreen, HomeScreen, MessagingScreen, NeighborsScreen, LogScreen, SimulationScreen
- `app/src/main/java/com/meshlink/app/ui/components/`: NodeIdentityDialog, PacketInspectorDialog, StatusBadges (theme-aware colors)
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` (exclude received-message storage from backup)

## Physical verification and limits

No physical phones were available: direct and multi-hop BLE notification delivery, OEM lock-screen presentation, real permission-denial behavior while BLE is active, and older supported Android versions remain to be physically verified. Emulator notification tests exercise Android posting/tap behavior; protocol unit tests exercise validated delivery eligibility. They do not constitute a radio test.

The persisted inbox retains the latest 100 accepted messages and 2,048 notification deduplication keys. Older evicted messages are unavailable to notification taps; deduplication is bounded rather than unlimited. Existing AODV deduplication also remains in place. Lock-screen content depends on the user's Android channel/privacy settings. No conversations architecture exists, so taps select Messages and inspect the received packet.

Stopped for user testing feedback and approval.
