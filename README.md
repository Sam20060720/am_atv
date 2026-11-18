# AM for TV

Listen to Apple Music on Android TV. Use your remote to find songs and follow word-by-word lyrics.

English · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md)

## Features

- Library, search, playlists, radio, playback queue, and listening history
- Word-by-word lyrics (backing vocals, long notes, interludes, timing offset)
- Artwork colors, animated backgrounds, and audio spectrum bars
- Autoplay recommendations (Apple Music / YouTube Music)
- English, Traditional Chinese, and Simplified Chinese

## Install

[Download the latest APKs](https://github.com/Sam20060720/am_atv/releases/latest).

Use the standard APK on Android 7 or later. Choose the bundled-font APK for Android 6 or to use Noto Sans CJK TC. Playback on Android 6 and 7 has yet to be verified on physical TVs.

Playback requires an Apple Music subscription. To sign in by QR code, connect your phone and TV to the same Wi-Fi network. You can also sign in on your TV.

## Screenshots

| Home | Player |
| --- | --- |
| ![Home screen](docs/assets/home.png) | ![Player with lyrics](docs/assets/player-lyrics.png) |

| Playlist | Queue |
| --- | --- |
| ![Playlist tracks](docs/assets/playlist.png) | ![Playback queue](docs/assets/queue.png) |

![Playback demo](docs/assets/player-demo.webp)

## Microphone permission

The visualizer uses `RECORD_AUDIO` to read the spectrum of the audio you play.

## Build

See [Build AM for TV](docs/build.md) for SDK setup, signing, and APK packaging.

## License

[MIT](LICENSE) · [Third-party licenses](app/src/main/assets/licenses/NOTICE.txt)
