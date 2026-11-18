# AM for TV

在 Android TV 上用遙控器聽 Apple Music、找歌和看逐字歌詞。

[English](README.md) · 繁體中文 · [简体中文](README.zh-CN.md)

## 功能

- 媒體庫、搜尋、歌單、廣播、待播清單與播放紀錄
- 逐字歌詞（和聲、長音、間奏、時間偏移）
- 封面取色背景與音樂頻譜動畫
- 自動接播推薦（Apple Music／YouTube Music）
- 支援英文、繁體中文和簡體中文

## 安裝

[下載最新版 APK](https://github.com/Sam20060720/am_atv/releases/latest)。

Android 7 以上可下載標準版。Android 6 或想使用 Noto Sans CJK TC 字型，請下載內建字型版。Android 6、7 的實體電視播放仍待確認。

播放需要 Apple Music 訂閱。手機與電視連上同一個 Wi-Fi 後即可掃 QR Code 登入，也可以直接在電視登入。

## 截圖

| 主畫面 | 播放頁面 |
| --- | --- |
| ![主畫面](docs/assets/home.png) | ![播放頁面與歌詞](docs/assets/player-lyrics.png) |

| 歌單 | 播放佇列 |
| --- | --- |
| ![歌單曲目](docs/assets/playlist.png) | ![播放佇列](docs/assets/queue.png) |

![播放示範](docs/assets/player-demo.webp)

## 麥克風權限

音樂律動使用 `RECORD_AUDIO` 權限讀取播放中的音訊頻譜。

## 建置

SDK 設定、簽章和打包步驟見 [建置指南](docs/build.md)。

## 授權

[MIT](LICENSE) · [第三方授權](app/src/main/assets/licenses/NOTICE.txt)
