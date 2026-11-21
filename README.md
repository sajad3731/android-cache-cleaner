# Cache Cleaner

A small Android app that helps you manage storage safely.

## What it can do

- Show and clear this app's own cache.
- Open Android's settings page for this app, where Android provides storage controls.

Android does not let ordinary third-party apps silently clear the caches of other apps. This app does not claim to perform a system-wide cleanup and does not request broad storage permissions. To clear another app's cache, open that app's storage settings and use the controls provided by your device.

## Build and run

Open the project in Android Studio and run the `app` configuration on a device or emulator (Android 6.0 / API 23 or newer). You can also build a debug APK with:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
