# AM for TV

**The smoothest Apple Music client for Android TV**

Offers an experience close to the native Apple Music app on Apple TV.

English · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md)

## Features

- Library, search, playlists, playback queue, and listening history
- Word-by-word lyrics (backing vocals, long notes, interludes, timing offset)
- Artwork colors, animated backgrounds, and audio spectrum bars
- Autoplay recommendations (Apple Music / YouTube Music)
- Artwork and lyric caches; English, Traditional Chinese, and Simplified Chinese

## Install

Use the standard APK on Android 7 or later. Choose the bundled-font APK for Android 6 or to use Noto Sans CJK TC. Both support phone QR and TV sign-in and require an Apple Music subscription. Playback on Android 6 and 7 has yet to be verified on physical TVs.

## Screenshots

| Home | Player |
| --- | --- |
| ![Home screen](docs/assets/home.png) | ![Player with lyrics](docs/assets/player-lyrics.png) |

| Playlist | Queue |
| --- | --- |
| ![Playlist tracks](docs/assets/playlist.png) | ![Playback queue](docs/assets/queue.png) |

![Playback demo](docs/assets/player-demo.webp)

## Microphone permission

`RECORD_AUDIO` is used to analyze playback audio for the visualizer. The physical microphone is not used.

## Build

Requires JDK 17, Android SDK 36, and the official [MusicKit Android SDK](https://developer.apple.com/musickit/android/). See [MusicKit setup](docs/musickit-setup.md) for credentials.

```sh
./gradlew :app:assembleDebug
./gradlew :app:distApks
```

The standalone APK is written to `dist/`. See [APK and source distribution](docs/standalone-distribution.md) for signing and packaging.

## License

[MIT](LICENSE) · [Third-party licenses](app/src/main/assets/licenses/NOTICE.txt)
