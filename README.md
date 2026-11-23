# Cache Cleaner

A free Android app that finds the cache of every installed app and clears it.

## What it does

- Scans all installed apps and shows how much cache each one holds, largest first, with the total.
- **Clean all** clears the cache of every app that holds any; each row also has its own **Clear** button.
- **Junk files**: leftover folders of removed apps, app installers (`.apk`), thumbnail caches, empty folders and old large downloads on shared storage, grouped with sizes. You pick what to delete.
- **Advanced** screen: one-tap cleaning status, appearance, a size limit and apps for Clean all to skip (long-press a row on the main screen too), a daily or weekly cleaning reminder, and a history of freed space.
- English and Persian interface (follows the phone language, right-to-left in Persian).

Only cache is cleared. Data apps keep elsewhere (downloads, offline media, logins) is never touched. Everything works on a normal phone: no root, no adb, no Developer options.

## How cleaning works

Android lets no normal app clear another app's cache. The public `StorageManager.allocateBytes` route only frees cache above a reserve of up to 5 GiB, and `ACTION_CLEAR_APP_CACHE` only reaches caches on shared storage, so neither clears what users expect.

Cache Cleaner therefore uses an Accessibility service that does what the user would do: it opens each app's Storage page in Settings and presses Clear cache. Where Settings offers it (Samsung, AOSP 11+), each app's Storage page opens directly through `com.android.settings.APP_STORAGE_SETTINGS`, skipping App info. Each step reacts to Settings' window events instead of waiting fixed delays, and moves on as soon as the button turns grey: about 0.7 s per app on a Galaxy S24 Ultra (227 apps in about 3 minutes). An app that fails is retried once at the end; apps still skipped are named in the report. A full-screen overlay covers Settings while it works, shows progress, and has a Stop button. Until the service is turned on, each row's Clear button can open that app's Settings page instead.

The button is found by an id that only Clear cache uses (`cache_button` on Samsung) or by Settings' own localized label, never by position, and a button or dialog with Clear data / Clear storage text in it is never pressed. Labels are read from the package that shows App info (`storage_label`, `storage_settings_for_app`, `clear_cache_btn_text` and OEM variants), with English and Persian fallbacks:

- **Samsung (One UI)**: tested on a Galaxy S24 Ultra.
- **Pixel / AOSP, Oppo / OnePlus (ColorOS), Vivo**: Storage (or Storage & cache / Storage usage) row, then the Clear cache button; a "Clear cache?" confirmation is accepted only when it mentions cache and not data. Not tested on a device yet.
- **Xiaomi (MIUI, HyperOS)**: App info lives in the Security app (`com.miui.securitycenter`) and has no Storage page. Its bottom Clear data button only opens a menu; in it, only Clear cache is pressed. Not tested on a device yet.

### Junk files

With **All files access** (`MANAGE_EXTERNAL_STORAGE`; the storage permission on Android 8 to 10), the scanner walks shared storage and groups:

- leftovers of removed apps: `Android/media/<package>` and top-level folders named like a package (`com.example.app`) that no installed app's package equals, starts with or extends (not selected by default);
- `.apk`, `.apks`, `.xapk`, `.apkm` files (only an `.apk` of an app installed at the same or a newer version is selected by default);
- `.thumbnails` folders (contents only; Android rebuilds them);
- empty folders (standard folders such as `DCIM` or `Download` stay; a folder that gained a file since the scan is kept);
- files in `Download` of at least 50 MB unchanged for 90 days (not selected by default).

`Android/data` and `Android/obb` are never read or touched. Each group can be expanded to check its items before deleting.

### Cleaning reminder

A periodic `JobScheduler` job (daily or weekly, first run after one period) measures the cache Clean all would free and, from 50 MB, posts a notification. Tapping it opens the app and starts Clean all. The job never cleans by itself: the Accessibility engine works through Settings on screen, which must never happen without the user. Android 13+ asks for the notification permission when the reminder is turned on.

## Permissions

- **Usage access**: Android needs it to report other apps' cache sizes.
- **Accessibility service**: presses Clear cache in Settings, only during a cleaning the user started.
- **All files access** (`MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` up to Android 10): Junk files only.
- `QUERY_ALL_PACKAGES`: so the scan covers every installed app, system apps included.
- `POST_NOTIFICATIONS`: the cleaning reminder.
- `RECEIVE_BOOT_COMPLETED`: keeps the reminder after a restart.

## Android and app stores

The app targets Android 16 (API 36) and supports Android 8.0 (API 26) and newer. It is aimed at Cafe Bazaar and Myket first. Google Play restricts `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE` and Accessibility services that are not accessibility tools, so a Play release needs declarations that Google may reject.

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
