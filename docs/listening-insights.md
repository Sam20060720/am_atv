# 聆聽紀錄、Replay 與個人推薦

資料契約由Context7與Apple官方DocC JSON交叉核對。本文記錄目前實作與公開資料邊界，不表示所有帳號都有回顧資料，也不以解析或建置成功代替實際裝置驗證。

## 公開來源

| 項目 | 官方端點與條件 | 原生承接 |
| --- | --- | --- |
| 最近歌曲 | GET `/v1/me/recent/played/tracks`；必填 types，支援 songs、library-songs、music-videos、library-music-videos；上限 30 | 依真實 next 分頁，來源保留全部類型，畫面排除目前不能播放的影片 |
| 最近項目 | GET `/v1/me/recent/played`；必填 types，支援 artists、curators、albums、library-albums、playlists、library-playlists、stations；上限 10 | 保留資源類型，歌曲／專輯／歌單／藝人照既有路由開啟 |
| 經常聆聽 | GET `/v1/me/history/heavy-rotation` | 讀取服務結果及 next；空資料不換成任意固定歌單 |
| 最近電台 | GET `/v1/me/recent/radio-stations` | 資料介面保留；原生電台不可播時隱藏這個來源及電台卡片 |
| 個人推薦 | GET `/v1/me/recommendations`、`/{id}`、`/{id}/contents` | 根清單及每個內容群各自分頁；群組可以繼續深入，標題與推薦理由來自服務 |
| Replay | GET `/v1/me/music-summaries?filter[year]=latest`，可啟用 top-artists、top-albums、top-songs views | 先載入實際 summaries，再只請求選取的 view；PeriodSummary 關聯才是可開啟的作品／藝人 |

官方文件：[最近歌曲](https://developer.apple.com/documentation/applemusicapi/get-v1-me-recent-played-tracks)、[最近項目](https://developer.apple.com/documentation/applemusicapi/get-recently-played-resources)、[Heavy Rotation](https://developer.apple.com/documentation/applemusicapi/get-heavy-rotation-content)、[最近電台](https://developer.apple.com/documentation/applemusicapi/get-recently-played-stations)、[個人推薦根清單](https://developer.apple.com/documentation/applemusicapi/get-all-recommendations)、[單一推薦](https://developer.apple.com/documentation/applemusicapi/get-a-recommendation)、[推薦 contents](https://developer.apple.com/documentation/applemusicapi/get-a-relationship-on-the-recommendation)、[Replay](https://developer.apple.com/documentation/applemusicapi/get-the-user%27s-replay-data)。

## Replay 的資料形狀

`MusicSummaries.attributes` 公開欄位是 `year` 與 `period`，period 可為 year／month。公開請求的 filter[year] 固定為 latest，回傳的是 Apple 判定有足夠紀錄的最新年份；不能把裝置目前年份、任意歷年選單或私人網站端點代入。

top-songs 資料是 `song-period-summaries`，其 `relationships.song.data` 才是 songs；album／artist 同理。這三個公開 PeriodSummaries schema 沒有播放次數、分鐘或名次屬性。畫面維持服務順序，僅顯示真正取得的年份／期間／作品，不產生虛構數字。關聯只有 identifier 時，依類型每批最多 25 個 ID 補齊 catalog metadata，不為每張卡片送一次請求。

所選 view 同時依官方 [scoped include](https://developer.apple.com/documentation/applemusicapi/handling-resource-representation-and-relationships) 規則請求其 PeriodSummary 關聯，例如 `include[song-period-summaries]=song`；最近播放的混合資源也只對 library 類型加入 catalog 關聯，避免把 catalog 當成所有 primary resource 都存在的 relationship。服務若仍未回傳關聯，保留 wrapper 並標記部分資料未提供。

官方 schema：[MusicSummaries](https://developer.apple.com/documentation/applemusicapi/musicsummaries)、[attributes](https://developer.apple.com/documentation/applemusicapi/musicsummaries/attributes-data.dictionary)、[TopSongsView](https://developer.apple.com/documentation/applemusicapi/musicsummaries/views-data.dictionary/topsongsview)、[SongPeriodSummaries](https://developer.apple.com/documentation/applemusicapi/songperiodsummaries)、[AlbumPeriodSummaries](https://developer.apple.com/documentation/applemusicapi/albumperiodsummaries)、[ArtistPeriodSummaries](https://developer.apple.com/documentation/applemusicapi/artistperiodsummaries)。

成功但 data 空、選取 view 未提供、最新結果已沒有選取 summary，以及授權／網路／資源失敗都有不同狀態。401／403 不被當作「沒有資格」，200 空也不推斷原因。找不到的 catalog 關聯保留 identifier 並標記部分內容無法顯示，不以虛構作品填補。

## 分頁與效能

`AccountInsightsApi` 重用 AppleMusicApi 的 authenticated transport、storefront 與 parser。`ListeningPageCursor` 將 cursor 綁定到原來源、推薦群組或 Replay summary/view；驗證 HTTPS、Apple API host、port、user info、fragment 及 endpoint。next 原有 query 保留，不將服務的 opaque offset 轉成數字；若服務省略必填 types 或原先 scoped include，只補上缺少的參數。首次 Replay view 的 next 可以使用服務回傳的 view 路徑，後續 cursor 必須保持該 endpoint。重複 cursor 會停止並保留重試狀態。

`ListeningInsightsBrowser` 只為可見頁面保留一條請求鏈，切換來源、返回群組、選擇其他 Replay view 或隱藏頁面即取消。只載入選取來源及使用者要求的下一頁；不輪詢歷史、不預抓所有推薦後代、不一次載入三個 Replay views。推薦根清單只保留群組ID、標題、推薦理由與resourceTypes；開啟群組才讀取該群組的contents，並批次補齊當頁缺少metadata的資源。contents中的子群組也只保留群組metadata，開啟後才讀取其contents。

`ListeningInsightsScreen`直接顯示所選首頁棚標題，不再插入無關來源側欄。共享`CatalogCollectionBody`以純歌曲直向列表、其他資源多列網格呈現，沿用ArtistCover／FocusSurface及既有動態封面。推薦子群組、Replay年份／summary與歌曲／專輯／藝人切換保留；每個visit／view記住捲位與穩定項目焦點，Back先退出子群組，再回首頁原入口。Replay僅保留當前summary的三個已載view，不預抓其他view；切換summary清理舊狀態。

來源研究副本在忽略版控的 `work/apple-music-web/insights-docs/`，包括官方原始 DocC JSON；沒有把網站私人 endpoint 或帳號 token 放入產品及文件。

首頁只有同一次資料回應提供真next、total大於原始資料筆數，或存在未直接呈現的子推薦群組時才顯示「顯示全部」。完整小棚不建立重複頁面入口，也不為了製造差異裁切首頁。子群組的葉項可能已在首頁攤平顯示，群組層級本身仍須開啟父群組才能瀏覽。
