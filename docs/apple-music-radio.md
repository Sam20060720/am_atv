# Apple Music 廣播

廣播頁可瀏覽直播、個人電台及最近播放的電台。Apple 提供哪些電台和節目取決於帳號地區。

## 電台來源

| 分類 | 路徑 |
| --- | --- |
| Apple Music 直播 | `/v1/catalog/{storefront}/stations?filter[featured]=apple-music-live-radio` |
| 個人電台 | `/v1/catalog/{storefront}/stations?filter[identity]=personal` |
| 最近播放 | `/v1/me/recent/radio-stations` |

`RadioCatalogApi` 讀取上述分類。廣播頁也讀取 Apple 的公開廣播頁面，用其中的電台或節目 ID 開啟播放。登入後，播放設定和直播曲名與封面查詢使用同一個帳號地區。

## 播放與重連

- 選擇電台後，`MusicKitStationPlayer` 建立 WebView，載入 Apple 的 MusicKit JS，並以 `setQueue({station: ...})` 開啟串流。瀏覽列表時無須啟動播放器。
- 實況直播的暫停會停止串流。再次播放會重新連到同一電台的直播進度。直播無法拖曳進度，節目單集能否拖曳則依播放器回報決定。
- 使用者仍選擇播放，而串流停止或長時間沒有進度時，App 會嘗試重連。使用者手動暫停或節目正常結束後，等待下一次播放指令。
- 播放中畫面分別讀取電台身分和當前曲名。更新封面時，以直播資料中對應帳號地區的歌曲 ID 查詢。排查曲名或封面錯誤時，可先核對兩處使用的 `storefront`。
