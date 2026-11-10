# Developer setup and validation

Use JDK 17, SDK Platform 34, and the Gradle 8.4 wrapper. Configure ANDROID_HOME or an untracked local.properties. Dependency downloads require network access on a first build; offline mode requires a populated local cache.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin --offline
```

Unit results: `app/build/reports/tests/testDebugUnitTest/index.html`.
Lint report: `app/build/reports/lint-results-debug.html`.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

`compileDebugAndroidTestKotlin` compiles instrumentation tests; it does not run them. With an appropriate connected device, use `./gradlew connectedDebugAndroidTest`. SpeechDraftTest and SpeechLevelTest exercise speech state and RMS processing, not a device's recognizer service. Validate recognizer availability, error recovery, waveform callbacks, two-device messaging, relay routes, and background behavior on physical hardware.

No GitHub Actions workflow exists in this checkout. Keep the above command as the local validation baseline; a hosted CI build would need dependency downloads and SDK setup rather than assuming an offline cache. No new automation is required for this documentation refresh.

Never include APKs, local configuration, signing keys, or caches in source commits. Review the exact proposed inventory in verification/RELEASE_PREPARATION.md before staging; do not blindly add every untracked file.
