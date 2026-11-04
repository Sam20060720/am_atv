# MusicKit setup

1. Create a [Media ID and MusicKit key](https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key).
2. Download the [MusicKit Android SDK](https://developer.apple.com/musickit/android/)
   and place its AAR files in `app/libs/`.
3. Keep the `.p8` key outside the repository. Copy `developer.env.example` to a
   private location and fill in your Team ID, Key ID and absolute private-key path.
4. Generate the signed developer credential with Node.js 20.6 or later:

   ```sh
   node --env-file=/private/path/developer.env \
     tools/export-developer-token.mjs /private/path/developer-token.jwt
   ```

5. Set the credential file and anonymous browsing storefront in the ignored `local.properties`:

   ```properties
   sdk.dir=/absolute/path/to/android-sdk
   AM_DEVELOPER_TOKEN_FILE=/private/path/developer-token.jwt
   AM_STOREFRONT=tw
   ```

   `AM_STOREFRONT` is only the signed-out browsing default
   (`BuildConfig.APPLE_MUSIC_GUEST_STOREFRONT`). Signed-in catalog, lyrics, radio
   and library management use the account's `/v1/me/storefront` result. A failed
   account lookup is reported instead of silently changing the country.

6. Run `tools/build-standalone.sh`, install the APK, and sign in on the TV.

The exporter prints the credential's expiry. Rebuild and distribute an updated APK
before it expires. The private key and Music User Tokens are never bundled.

Google account pairing for YouTube Music is optional. A developer can configure
`YTM_OAUTH_CLIENT_ID` and `YTM_OAUTH_CLIENT_SECRET` for their own TV OAuth client;
Apple Music playback does not require it.

The supported MusicKit playback SDK is checked before each build. See
[catalog-ID compatibility](../buildSrc/README.md) before changing its version.
