# EnterSend

Android accessibility service for phones with a physical keyboard (primary target: Unihertz Q25, Android 14).
In chat text fields, **Enter sends** the message and **Shift+Enter inserts a newline**. Everywhere else Enter behaves normally.

- Package: `com.ovehbe.entersend`
- Kotlin, Gradle, minSdk 29, targetSdk 35
- No internet permission; all data stays on the device.

## Build

Requirements: JDK 17+ and an Android SDK with platform 35 (`ANDROID_HOME` set, or `sdk.dir` in `local.properties`).

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
