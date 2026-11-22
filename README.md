# Cache Cleaner

A free Android app that finds the cache of every installed app and clears it.

## What it does

- Scans all installed apps and shows how much cache each one holds, largest first, with the total.
- **Clean all** clears the cache of every app with at least 1 MB of cache; each row also has its own **Clear** button.
- **Advanced** screen: Shizuku engine, a size limit and apps for Clean all to skip (long-press a row on the main screen too), a daily or weekly scheduled clean, and a history of freed space.
- English and Persian interface (follows the phone language, right-to-left in Persian).

Only cache is cleared. Data apps keep elsewhere (downloads, offline media, logins) is never touched.

## How cleaning works

Android lets no normal app clear another app's cache. The public `StorageManager.allocateBytes` route only frees cache above a reserve of up to 5 GiB, and `ACTION_CLEAR_APP_CACHE` only reaches caches on shared storage, so neither clears what users expect.

Cache Cleaner therefore uses an Accessibility service that does what the user would do: it opens each app's Storage page in Settings and presses Clear cache. It finds the button by its view id (`cache_button` on Samsung) or by Settings' own localized "Clear cache" text, never by position, so Clear data can never be pressed. A full-screen overlay covers Settings while it works, shows progress, and has a Stop button. Until the service is turned on, each row's Clear button can open that app's Settings page instead.

### Shizuku engine (optional)

With [Shizuku](https://shizuku.rikka.app/) running and allowed, a helper process (`ShellService`, an AIDL user service) runs as shell or root:

- **adb mode (shell)**: Android 14 and newer ignore per-app cache clearing from shell (`pm clear --cache-only` logs `INTERNAL_DELETE_CACHE_FILES ... silently ignoring` and hangs). What shell can do is `pm trim-caches`, which frees every app's cache at once in a few seconds. Clean all uses it, unless an app the user skips currently holds cache; then Clean all falls back to the Accessibility engine so that app stays untouched. Clear buttons always use Accessibility.
- **root mode**: Clean all and each Clear button run `pm clear --cache-only` app by app in the background, respecting the size limit and skipped apps.

Scheduled cleans (`JobScheduler`, daily or weekly, optionally only while charging) run through Shizuku only, since the Accessibility engine needs the screen. A run without Shizuku is skipped and noted in the history.

## Permissions

- **Usage access**: Android needs it to report other apps' cache sizes.
- **Accessibility service**: presses Clear cache in Settings, only during a cleaning the user started.
- `QUERY_ALL_PACKAGES`: so the scan covers every installed app, system apps included.
- **Shizuku** (optional, granted in the Shizuku app): runs `pm trim-caches` or `pm clear --cache-only`.
- `RECEIVE_BOOT_COMPLETED`: keeps a scheduled clean after a restart.

## Android and app stores

The app targets Android 16 (API 36) and supports Android 8.0 (API 26) and newer. It is aimed at Cafe Bazaar and Myket first. Google Play restricts both `QUERY_ALL_PACKAGES` and Accessibility services that are not accessibility tools, so a Play release needs declarations that Google may reject.

On Android 13 and newer, a sideloaded APK must first get **Allow restricted settings** (App info, ⋮) before its Accessibility service can be turned on.

## Privacy policy

The [privacy policy](PRIVACY.md) is also available at [https://github.com/sajad3731/android-cache-cleaner/blob/main/PRIVACY.md](https://github.com/sajad3731/android-cache-cleaner/blob/main/PRIVACY.md). Enter this public URL in the Play Console and complete the Data safety section based on the exact version submitted.

## Build and run

Open the project in a current Android Studio with the Android 16 (API 36) SDK installed, then run the `app` configuration on a device or emulator. You can also build a debug APK with:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

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
