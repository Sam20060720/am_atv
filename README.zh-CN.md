# AM for TV

在 Android TV 上用遥控器听 Apple Music、找歌和看逐字歌词。

[English](README.md) · [繁體中文](README.zh-TW.md) · 简体中文

## 功能

- 资料库、搜索、歌单、电台、播放队列与播放记录
- 逐字歌词（和声、长音、间奏、时间偏移）
- 封面取色背景与音乐频谱动画
- 自动接播推荐（Apple Music／YouTube Music）
- 支持英语、繁体中文和简体中文

## 安装

[下载最新版 APK](https://github.com/Sam20060720/am_atv/releases/latest)。

Android 7 及以上可以下载标准版。Android 6 或想使用 Noto Sans CJK TC 字体，请下载内置字体版。Android 6、7 的真机播放仍待确认。

播放需要 Apple Music 订阅。手机和电视连上同一个 Wi-Fi 后即可扫码登录，也可以直接在电视上登录。

## 界面

| 主界面 | 播放页面 |
| --- | --- |
| ![主界面](docs/assets/home.png) | ![播放页面与歌词](docs/assets/player-lyrics.png) |

| 歌单 | 播放队列 |
| --- | --- |
| ![歌单曲目](docs/assets/playlist.png) | ![播放队列](docs/assets/queue.png) |

![播放演示](docs/assets/player-demo.webp)

## 麦克风权限

音乐律动使用 `RECORD_AUDIO` 权限读取正在播放的音频频谱。

## 构建

SDK 设置、签名和打包步骤见 [构建指南](docs/build.md)。

## 许可

[MIT](LICENSE) · [第三方许可](app/src/main/assets/licenses/NOTICE.txt)
