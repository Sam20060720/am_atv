# AM for TV

**Android TV 上最流畅的 Apple Music 客户端**

提供接近 Apple TV 原生 Apple Music 的使用体验。

[English](README.md) · [繁體中文](README.zh-TW.md) · 简体中文

## 功能

- 资料库、搜索、歌单、播放队列与播放记录
- 逐字歌词（和声、长音、间奏、时间偏移）
- 封面取色背景与音乐频谱动画
- 自动接播推荐（Apple Music／YouTube Music）
- 封面／歌词缓存，英文／繁中／简中界面

## 安装

Android 7 及以上选择标准版。Android 6 或想使用内置 Noto Sans CJK TC 字体时，选择内置字体版。两版均支持手机扫码或在电视上登录，需要 Apple Music 订阅。Android 6、7 的真机流媒体播放仍待验证。

## 界面

| 主界面 | 播放页面 |
| --- | --- |
| ![主界面](docs/assets/home.png) | ![播放页面与歌词](docs/assets/player-lyrics.png) |

| 歌单 | 播放队列 |
| --- | --- |
| ![歌单曲目](docs/assets/playlist.png) | ![播放队列](docs/assets/queue.png) |

![播放演示](docs/assets/player-demo.webp)

## 麦克风权限

`RECORD_AUDIO` 用于播放音频的频谱分析，不使用实体麦克风。

## 构建

需要 JDK 17、Android SDK 36 及官方 [MusicKit Android SDK](https://developer.apple.com/musickit/android/)。凭证设置见 [MusicKit 设置](docs/musickit-setup.md)。

```sh
./gradlew :app:assembleDebug
./gradlew :app:distApks
```

独立版 APK 输出至 `dist/`。签名与打包方式见 [APK 与源码分发](docs/standalone-distribution.md)。

## 许可

[MIT](LICENSE) · [第三方许可](app/src/main/assets/licenses/NOTICE.txt)
