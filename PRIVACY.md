# Privacy Policy

Last updated: November 23, 2026

Cache Cleaner does not collect, transmit, or share personal data. It has no account, advertising, analytics, tracking, or internet permission. The only library it includes is Shizuku's open-source API, which talks to the Shizuku app on the same device and sends nothing off it.

## Permissions and what they are used for

- **Usage access** (`PACKAGE_USAGE_STATS`): lets Android report each installed app's cache size to Cache Cleaner. The app does not read app usage history.
- **Installed apps list** (`QUERY_ALL_PACKAGES`): used only to show each app's name, icon, and cache size in the scan results.
- **Accessibility service**: used only to open the Storage page of each app you chose to clean in Android Settings and press Clear cache. It acts only on Settings screens and only while a cleaning you started is running. It does not read, keep, or send any other screen content.
- **Shizuku** (optional): if you install Shizuku and allow Cache Cleaner in it, the app uses it only to run Android's own cache commands (`pm trim-caches`, and `pm clear --cache-only` with root).
- **Run at startup** (`RECEIVE_BOOT_COMPLETED`): keeps a scheduled clean you turned on after the phone restarts.

All scanning and cleaning happens on the device. Your settings (size limit, apps to skip, schedule) and a history of cleanings (date, freed space, number of apps) are kept on the device only, and you can clear the history in the app. Nothing is sent anywhere.

For privacy questions or requests, contact the publisher through [GitHub Issues](https://github.com/sajad3731/android-cache-cleaner/issues).
