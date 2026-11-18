# Build AM for TV

Install JDK 17 and Android SDK 36. Download the official [MusicKit Android SDK](https://developer.apple.com/musickit/android/) and place its AAR files in `app/libs/`.

## Configure Apple Music

Create a [Media ID and MusicKit key](https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key). Store the key outside the repository and set these values in the ignored `local.properties` file.

```properties
sdk.dir=/absolute/path/to/android-sdk
APPLE_TEAM_ID=YOUR_TEAM_ID
APPLE_MUSIC_KEY_ID=YOUR_KEY_ID
APPLE_MUSIC_PRIVATE_KEY_PATH=/private/path/AuthKey.p8
AM_DEVELOPER_TOKEN_FILE=/private/path/developer-token.jwt
AM_STOREFRONT=tw
```

Run `./gradlew :app:generateMusicKitToken` to create or renew the token. If you already have a signed token, set `AM_DEVELOPER_TOKEN_FILE` to its path. `AM_STOREFRONT` sets the region for signed-out browsing. After sign-in, the app reads the account region from `/v1/me/storefront`.

## Build and install

```sh
./gradlew :app:assembleDebug
./gradlew :app:distApks
```

On Windows, use `gradlew.bat`. The `distApks` task builds and copies both APKs to `dist/`. Each APK includes ARM32 and ARM64 libraries.

| APK | Minimum Android version | Fonts |
| --- | --- | --- |
| `AMATV-<version>.apk` | Android 7 | TV system fonts |
| `AMATV-<version>-font.apk` | Android 6 | Bundled Noto Sans CJK TC |

Connect the TV through ADB, then run the install task for the APK you want.

```sh
./gradlew :app:installStandaloneSystem # Standard
./gradlew :app:installStandalone       # Bundled fonts
```

Both standalone builds use the local debug signing key and the same app ID. Keep the same signing key when updating the app or switching between APKs to preserve app data. Rebuild before the bundled developer token expires.

YouTube Music recommendations can use a separate Google TV OAuth client. Set `YTM_OAUTH_CLIENT_ID` and `YTM_OAUTH_CLIENT_SECRET` in `local.properties` to enable pairing.

Test commands and trace setup are in [Tests](testing.md). See [Build patches](../buildSrc/README.md) before upgrading MusicKit or Compose Foundation.

## App reference

- [TV remote controls](tv-navigation.md)
- [Phone sign-in](phone-music-authorization.md)
- [Audio quality](audio-quality.md)
- [Radio](apple-music-radio.md)
- [Library and listening history API](library-api.md)
