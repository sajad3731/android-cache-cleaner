# Cache Cleaner

A small Android app that helps you manage storage safely.

## What it can do

- Show and clear this app's own cache.
- Open this app's Android settings page.

Android does not let ordinary third-party apps silently clear the caches of other apps. This app does not claim to perform a system-wide cleanup and does not request broad storage permissions. To clear another app's cache, navigate to Android Settings > Apps > [app] > Storage and use that app's Clear cache control; labels vary by device.

## Android and Google Play

The app targets Android 16 (API 36) and supports Android 6.0 (API 23) and newer. It does not use an Accessibility service, device-admin access, or broad storage permissions, and it does not automate Settings. Clearing another app's cache remains a user action in Android Settings.

This starter documents its behavior but is not a Google Play approval or policy review. The publisher must complete the Play listing and any applicable declarations before publishing.

## Privacy policy

The [privacy policy](PRIVACY.md) is also available at [https://github.com/sajad3731/android-cache-cleaner/blob/main/PRIVACY.md](https://github.com/sajad3731/android-cache-cleaner/blob/main/PRIVACY.md). Enter this public URL in the Play Console and complete the Data safety section based on the exact version submitted.

## Build and run

Open the project in a current Android Studio with the Android 16 (API 36) SDK installed, then run the `app` configuration on a device or emulator. You can also build a debug APK with:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests (cache size and delete logic, including symlink safety) with:

```sh
./gradlew testDebugUnitTest
```

## Release build

Release signing reads `keystore.properties` at the project root. It and any `*.jks` / `*.keystore` file are git-ignored; keep them and their passwords backed up outside the repo, since an upload key cannot be recovered.

Create an upload keystore once:

```sh
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties`:

```properties
storeFile=upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Build the Play bundle with:

```sh
./gradlew bundleRelease
```

The bundle is written to `app/build/outputs/bundle/release/app-release.aab`. Without `keystore.properties` the bundle is built unsigned.
