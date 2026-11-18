# AM for TV

**Android TV 上最流暢的 Apple Music 客戶端**

提供接近 Apple TV 原生 Apple Music 的使用體驗。

[English](README.md) · 繁體中文 · [简体中文](README.zh-CN.md)

## 功能

- 曲庫、搜尋、歌單、播放佇列與播放紀錄
- 逐字歌詞（和聲、長音、間奏、時間偏移）
- 封面取色背景與音樂頻譜動畫
- 自動接播推薦（Apple Music／YouTube Music）
- 封面／歌詞快取，英文／繁中／簡中介面

## 安裝

Android 7 以上選標準版。Android 6 或想使用內建 Noto Sans CJK TC 字型時，選內建字型版。兩版都支援手機掃 QR Code 或直接在電視登入，需 Apple Music 訂閱。Android 6、7 的實機串流播放仍待驗證。

## 截圖

| 主畫面 | 播放頁面 |
| --- | --- |
| ![主畫面](docs/assets/home.png) | ![播放頁面與歌詞](docs/assets/player-lyrics.png) |

| 歌單 | 播放佇列 |
| --- | --- |
| ![歌單曲目](docs/assets/playlist.png) | ![播放佇列](docs/assets/queue.png) |

![播放示範](docs/assets/player-demo.webp)

## 麥克風權限

`RECORD_AUDIO` 用於播放音訊的頻譜分析，不使用實體麥克風。

## 建置

需要 JDK 17、Android SDK 36 及官方 [MusicKit Android SDK](https://developer.apple.com/musickit/android/)。憑證設定見 [MusicKit 設定](docs/musickit-setup.md)。

```sh
./gradlew :app:assembleDebug
./gradlew :app:distApks
```

獨立版 APK 輸出至 `dist/`。簽章與打包方式見 [APK 與原始碼發佈](docs/standalone-distribution.md)。

## 授權

[MIT](LICENSE) · [第三方授權](app/src/main/assets/licenses/NOTICE.txt)
