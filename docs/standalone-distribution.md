# APK and source distribution

```sh
./gradlew :app:distApks
```

Gradle builds, signs, and copies both APKs to `dist/` using JDK 17, Android SDK 36,
the official MusicKit AARs, and the developer credential described in
[setup](musickit-setup.md). The Gradle wrapper works on Linux and macOS. On
Windows use `gradlew.bat :app:distApks`.

| APK | Minimum Android | Typography |
| --- | --- | --- |
| `AMATV-<version>.apk` (recommended) | 7.0 (API 24) | TV system fonts |
| `AMATV-<version>-font.apk` | 6.0 (API 23) | Bundled Noto Sans CJK TC |

Both APKs support ARM32 and ARM64, phone QR sign-in and sign-in through the TV's
system WebView. They share the application ID, version code, and signing identity,
so switching APKs preserves app data. Keep the signing key for updates. The
standalone builds use the existing local signing identity and an expiring
signed developer credential. Configure your own release signing identity for
distribution and rebuild before the credential expires.

On Android 7 and later, lyrics use Android's platform ICU and the standard APK
contains no ICU4J. The bundled-font APK includes ICU4J with only the data needed
for Android 6 lyrics script lookup. Both APKs use core library desugaring for
dates and Base64. Physical TV streaming playback on Android 6 and 7 still needs
verification.

The TV sign-in diagnostic console is hidden by default. Press the remote's MENU
key to show or hide it. Diagnostics are bounded in memory and redact credentials
and URL parameters.

To archive a committed source tree, use `git archive`. The archive contains
tracked project files without Git history, credentials or local data.

The Android SDK can install either built variant directly on a connected TV:

```sh
./gradlew :app:installStandaloneSystem # standard
./gradlew :app:installStandalone       # bundled fonts
```

The APKs include the baseline profile and AndroidX ProfileInstaller. After a
sideload, Android can use the profile for later background optimization.
