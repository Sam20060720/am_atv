# MusicKit setup

1. Create a [Media ID and MusicKit key](https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key).
2. Download the [MusicKit Android SDK](https://developer.apple.com/musickit/android/)
   and place its AAR files in `app/libs/`.
3. Keep the `.p8` key outside the repository. Configure your Android SDK, Apple
   identifiers and private paths in the ignored `local.properties`:

   ```properties
   sdk.dir=/absolute/path/to/android-sdk
   APPLE_TEAM_ID=YOUR_TEAM_ID
   APPLE_MUSIC_KEY_ID=YOUR_KEY_ID
   APPLE_MUSIC_PRIVATE_KEY_PATH=/private/path/AuthKey.p8
   AM_DEVELOPER_TOKEN_FILE=/private/path/developer-token.jwt
   AM_STOREFRONT=tw
   ```

   `AM_STOREFRONT` is only the signed-out browsing default
   (`BuildConfig.APPLE_MUSIC_GUEST_STOREFRONT`). Signed-in catalog, lyrics, radio
   and library management use the account's `/v1/me/storefront` result. A failed
   account lookup is reported instead of silently changing the country.

4. Run `./gradlew :app:generateMusicKitToken` to create or renew the credential.
   If you already have a signed token, point `AM_DEVELOPER_TOKEN_FILE` to it and
   skip this step.
5. Run `./gradlew :app:distApks`, choose an APK from `dist/`, and sign in using
   the phone QR code or on the TV.

The Gradle task prints the credential's expiry. Rebuild and distribute an updated APK
before it expires. The private key and Music User Tokens are never bundled.

Google account pairing for YouTube Music is optional. A developer can configure
`YTM_OAUTH_CLIENT_ID` and `YTM_OAUTH_CLIENT_SECRET` for their own TV OAuth client;
Apple Music playback does not require it.

The supported MusicKit playback SDK is checked before each build. See
[catalog-ID compatibility](../buildSrc/README.md) before changing its version.
