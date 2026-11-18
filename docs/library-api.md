# 媒體庫與聆聽紀錄 API

`AppleMusicApi` 使用 Developer Token 和 Music User Token 讀取個人媒體庫。以下是各分類的第一頁。

| 分類 | 路徑 |
| --- | --- |
| 最近加入 | `/v1/me/library/recently-added` |
| 專輯 | `/v1/me/library/albums` |
| 藝人 | `/v1/me/library/artists` |
| 所有歌單 | `/v1/me/library/playlists` |
| 歌單與資料夾的根目錄 | `/v1/me/library/playlist-folders/p.playlistsroot/children` |
| 歌曲 | `/v1/me/library/songs` |
| 已收藏的藝人專輯 | `/v1/me/library/artists/{libraryArtistId}/albums` |
| 搜尋媒體庫 | `/v1/me/library/search` |

## 項目 ID 與分頁

- 媒體庫項目以 `resourceType` 和 `resourceId` 為準。用 `catalogId` 補取目錄封面、藝人資訊或音軌。不同媒體庫項目可以對應同一個目錄 ID。歌曲沒有目錄 ID 時，播放會先嘗試用原始媒體庫 ID 查找對應曲目。
- 翻頁沿用伺服器回傳的 `next` 和完整查詢參數，並確認網址仍指向原來的端點。伺服器未回傳 `meta.total` 時，總筆數為未知。
- 一般瀏覽按頁載入。排序、文字篩選和字母跳轉會讀完所選分類再處理。媒體庫搜尋使用 Apple 的搜尋端點。
- 一般播放可先用已載入的一頁建立待播清單，再繼續讀取。大型歌單有可靠的原始總筆數時，隨機播放可按原始位置抽一首先播。歌單中重複出現的歌曲按位置保留。

## 修改媒體庫

- `CatalogLibraryManagement` 分別處理編輯與刪除權限。資料夾內新增項目、資料夾改名及刪除各有自己的條件。
- 寫入結果不明時，先重讀條目或所在資料夾，確認變更是否已生效。重新確認操作應發出讀取請求，以免建立重複歌單或再次追加歌曲。
- 刪除後，Apple 可能仍對原 ID 回傳 HTTP 200。確認刪除時一起查看該資源的狀態和原先所在的資料夾。

參考 [Apple Music API 分頁](https://developer.apple.com/documentation/applemusicapi/fetching-resources-by-page) 與 [資源和關聯](https://developer.apple.com/documentation/applemusicapi/handling-resource-representation-and-relationships)。

## 聆聽紀錄與 Replay

`AccountInsightsApi` 透過 `/v1/me/recent/played/tracks` 和 `/v1/me/recent/played` 讀取最近播放，從 `/v1/me/history/heavy-rotation` 讀取經常聆聽的內容。開啟個人推薦群組時，再向 `/v1/me/recommendations/{id}/contents` 載入該群組。

Replay 以 `/v1/me/music-summaries?filter[year]=latest` 讀取 Apple 提供的最新年份。歌曲、專輯與藝人各有一份可單獨載入的清單。清單項目是摘要，卡片的資料取自摘要中的歌曲、專輯或藝人關聯。Apple 的公開回應未提供播放次數、聆聽分鐘或名次。遇到空清單、登入失效或連線錯誤時，畫面會分別提示。參考 [Apple Music Replay API](https://developer.apple.com/documentation/applemusicapi/get-the-user%27s-replay-data)。
