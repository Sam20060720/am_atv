# Apple Music 網頁功能與動態封面

核對日期：2026-10-28。範圍是 Apple Music 台灣公開網頁、當日公開下發的 JavaScript，以及目前 AMATV 程式；沒有登入網站、修改資料庫或播放受保護內容。這是功能盤點與實作依據，不是「所有功能已完成」的宣告。公開 API 的端點與授權另見 [API 能力盤點](apple-music-capabilities.md)。

## 網頁提供的內容與入口

| 功能群 | 已觀察的網頁內容 | AMATV 需要承接的行為 |
| --- | --- | --- |
| 新發現與編輯內容 | 推薦編排、新歌、新專輯、即將推出、更新歌單、主題集合 | 保留伺服器標題與資源身分；卡片可進入真正內容，長列表依 next 載入 |
| 分類與排行榜 | 曲風、心情、活動、年代、特殊主題、全球／地區榜、城市榜 | 分類可瀏覽、有內容頁與返回路徑；不把所有分類混成歌曲搜尋 |
| 藝人 | 最新／必聽作品、熱門歌曲、專輯、單曲／EP、現場／合輯、參與作品、影片、精選歌單、相似藝人、訪談與電台 | 不能只列「藝人的專輯」；每個可取得的區塊須保留其類型、分頁與下一層入口 |
| 專輯 | 發行日期、曲風、版權、其他版本、相關影片、更多藝人作品、收錄歌單、相似作品 | 顯示真實 metadata；藝人、版本及相關內容能以遙控器開啟 |
| 播放列表 | 策展者、更新資訊、說明、歌曲數／總長、精選藝人 | 策展者和藝人是可導航資源；個人歌單保留 Library 身分與使用者說明 |
| 影片與演出 | 音樂影片、演唱會、訪談、預覽與時長 | 瀏覽資料和實際播放能力分開；不能把影片當作普通歌曲加入佇列 |
| 電台 | 直播品牌、節目、單集、訪談影片、地區廣播來源 | 須依資源種類與播放能力分派；未成功播放前不能以文字清單宣稱完成 |
| 搜尋 | 搜尋欄及內容分類入口 | API 支援的歌曲／專輯／藝人／歌單／影片／電台及個人資料庫搜尋應保留類別與各自 cursor |
| 登入後功能 | 公開匿名頁不足以證明個人資料庫、收藏、評分、播放列表編輯的完整操作 | 以公開 API 與實際授權能力接入，操作後更新本地狀態；本輪不對帳號執行寫入 |
| 播放與系統能力 | 公開頁的試聽入口不等於訂閱完整播放、音質、離線、Sing 或輸出裝置能力 | 維持 SDK 的能力判斷；未知項目保留為未驗證，不製造無作用按鈕 |

上述資料群的來源是 [新發現](https://music.apple.com/tw/new)、[搜尋／分類](https://music.apple.com/tw/search)、[藝人](https://music.apple.com/tw/artist/ariana-grande/412778295)、[專輯](https://music.apple.com/tw/album/positions-deluxe/1553944254)、[播放列表](https://music.apple.com/tw/playlist/a-list-%E8%8F%AF%E8%AA%9E%E6%B5%81%E8%A1%8C%E6%A8%82/pl.beb783da7712481fbeed35be144bd48c)、[廣播](https://music.apple.com/tw/radio)。內容會依地區、帳號、時間而變；公開頁不是私人 API 的穩定契約。

## 動態封面的實際網頁規則

當日直接下載的公開入口：`https://music.apple.com/assets/index~cc245af156.js`，3,344,359 bytes，SHA-256 `752cc36a456f41e8b648791ea1f890a0d8e4adef86c9bfc5409018da7ca94a93`。研究副本位於忽略版本控制的 `work/apple-music-web/source/index.js`；沒有把網頁程式碼加入產品。

以下是該檔案的零起算字元位置，屬於靜態宣告，未宣稱已在瀏覽器完整執行：

| 定位 | 觀察 |
| --- | --- |
| 26287、2005839 | 使用 reduced-motion 狀態；一般播放條件同時考慮視窗、可見範圍、暫停及全螢幕 modal；forceAnimation 為明確覆寫 |
| 2004125、2004651 | IntersectionObserver 預設門檻 0.6；文件隱藏、視窗失焦會改變播放條件 |
| 2005839、2010257 | 靜態封面保留在 ambient video 下方；影片是裝飾內容，從可用 source 與 profile 選取 |
| 2007053–2007480 | 一般方形／直式／寬式、藝人橫幅及容器詳情有不同資產選擇順序，不能只取第一個影片欄位 |
| 585659 起 `Vue` | 藝人詳情請求實際加上 artistBio、editorialArtwork、editorialVideo、extendedAssetUrls、plainEditorialNotes 等 extension；也有網頁專用 canvas 參數，不能一律視為公開 API |

藝人橫幅優先欄位為 `motionArtistFullscreen16x9`、`motionArtistWide16x9`；小版型另用 `motionArtistSquare1x1`。容器詳情使用 `motionDetailSquare`／`motionDetailTall`。這些是當日網頁觀察；只有 API 確實回傳時才啟用。

REA 對完整入口檔的靜態圖分析在 300 秒觀察期限內未返回；沒有重啟相同分析，也沒有成功圖 Evidence ID。上表由匹配 hash 的直接來源檢查建立，不能把它當作完整圖覆蓋或瀏覽器 runtime 證據。後續 session 狀態觀察也在 300 秒期限內未取得結果；未重啟或更改 REA 設定。

## 已修的動態封面問題

原本每個 `EditorialMotionVideo` 各建一個 ExoPlayer，AnimatedVisibility 退出期間會讓舊 decoder 與新卡片重疊；保留在背景的播放器畫面也可能在 Activity 仍 STARTED 時繼續解碼。

現在所有裝飾影片經由 `MotionPreviewCoordinator` 使用單一 renderer。最近加入的可見候選取得所有權；候選移除時恢復前一個仍可見候選，沒有候選時立即 stop 並解除 Surface，350ms 內沒有新候選再 release 輕量 renderer。所有權不依循音樂佇列，也不建立 MediaSession。

- 首個影片 frame 回報前保留靜態封面，準備好才淡入；錯誤時停播並清除媒體，避免白／黑面板和無限重試。
- 畫面隱藏、視窗失焦、生命週期停止及低效能模式均移除預覽資格。聚焦即開始讀取缺少的素材並準備影片，不另設固定停留或捲動停穩等待；離開焦點仍撤銷舊候選，單一 decoder 只接目前 owner。
- 音軌停用，影片靜音；video 選擇依實際卡片像素加少量縮放餘裕，並容納官方最小 rendition，最高 960px、30fps、2.5Mbps，仍禁止無上限的超規 fallback。無合適影片時仍顯示靜態封面。
- Sample buffer 目標 4 MiB、預緩衝 1–5 秒，不讓時間門檻繞過容量目標。這不是總 RSS 保證：原生 codec／texture 記憶體仍需裝置量測。裝飾影片現有 96 MiB LRU 磁碟快取；詳見下節。
- 失焦立即釋放候選，避免為退出動畫保留另一個 decoder；音樂播放服務、歌詞及 PlayerScreen 程式未修改。

Media3 實作依據：[Player.Listener 首 frame 事件](https://developer.android.com/reference/androidx/media3/common/Player.Listener)、[TrackSelectionParameters](https://developer.android.com/reference/androidx/media3/common/TrackSelectionParameters.Builder)、[DefaultTrackSelector 超規選擇](https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.Parameters.Builder)、[LoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder)。

## 尚未證明或尚未接完

此盤點沒有把匿名網頁的所有分頁、所有地區、所有登入後選單逐一實際點擊，也沒有執行寫入。各功能的目前接線狀態以 [API 能力盤點](apple-music-capabilities.md) 為準；未取得資料或實際播放證據的地方仍須保留驗證邊界，尤其是網頁專用 canvas、帳號寫入及 SDK 不開放的播放能力。

影片硬體 decoder 數量、frame time、弱網切換與真機 RSS 本輪未量測；本 agent 未操作模擬器或客廳電視。整合建置與必要裝置驗證由主 agent 執行，不能以這份文件或靜態參數宣稱已達官方效能。

## 原生站台／影片與 Sing 的邊界

目前實際使用的 `mediaplayback-release-1.1.1.aar` SHA-256 為 `39bc28cbc2413c0ce0477c1ef4d3b9e51bb163a7723d930c46bc089715116e3c`。直接以 JAR 公開介面檢查：container 只有 Album／Playlist，item 只有 Song／Uploaded Audio；沒有 RADIO_STATION、MUSIC_VIDEO 常數或 video-output Surface API。舊版官方 [常數文件](https://developer.apple.com/musickit/android/constant-values.html)與[控制器文件](https://developer.apple.com/musickit/android/com/apple/android/music/playback/controller/MediaPlayerController.html)列出更多功能，但與此 AAR 不符，不能據此填入數字 2／3 假裝可播。

新增的 `NativeMediaCapabilities` 讀取實際 SDK 符號，`NativeMediaRoute` 明確分辨 item、container、缺少能力與缺少 Surface。Bridge 在改動播放狀態前拒絕不支援的請求。`PlaybackSnapshot` 可回報原生 isLiveStream／canSeek；可用 request shape 仍不等於實際訂閱／DRM 播放成功。

歌曲種子電台由既有 `PlaybackQueueController` → `MusicServices.stationFromSong` 路徑處理，不把 catalog station ID 或電台 metadata 當成歌曲。未接線的 `GeneratedStationSongFeed` 包裝已於 11/03 移出 production，恢復副本保留在 `work/experience-performance/20261103/unused-media-scaffolding/`。

研究時官方頁面省略 slug 的 ID 路徑 `/tw/station/ra.1461200647` 與 `/tw/music-video/6796494529` 均得到 HTTP 200 並轉到 canonical URL。未接線、僅供未來模式使用的 `OfficialMediaFallback` 已於 11/03 移出 production，恢復副本同上；這項歷史 URL 觀察不能計入原生可用性。

依使用者最後更正「不能用就隱藏」，`CatalogPresentationCapabilities` 統一控制 Station／Music Video／Sing 的頁面入口、卡片與區塊。影片還須有 App 實際安裝的 video renderer；網路或授權暫時失敗不會讓普通歌曲消失。API 來源資料保留原樣。

Sing 的現行[官方分類入口](https://music.apple.com/tw/curator/sing/1710452798) ID 是 1710452798，實際 SSR descriptor 為 appleCurator，API 類型為 apple-curators（不是只根據 URL 推測 curators）；舊[說明 hub](https://music.apple.com/tw/multi-room/1631886925)則是不同頁面。其精選歌單可以當普通歌單瀏覽與播放，但不代表 Android App 有人聲分離滑桿。原生 Sing 入口在沒有此能力時隱藏，不用普通 TTML 歌詞冒充人聲分離。

快速切換的補強：每次媒體切換有獨立 media ID，首 frame 只更新對應 lease；錯誤回呼須仍是 player 的當前錯誤，才會停播。裝飾 TextureView 設為非 opaque，空 texture 仍透出靜態封面。這些是程式保護，尚非裝置競態壓測通過的宣稱。

後續只讀鏈路稽核確認並修正四個問題：

- 124dp 小卡片在 density=2 時，舊上限只有 310×310；實際官方 [square master](https://mvod.itunes.apple.com/itunes-assets/HLSMusic221/v4/92/71/c0/9271c0ee-0ebd-d5f8-1495-94b0eb64fd95/P1355057646_default.m3u8) 最小為 360×360，[fullscreen master](https://mvod.itunes.apple.com/itunes-assets/HLSMusic211/v4/ce/32/08/ce320856-0b62-bd14-c0ab-528b781acfa8/P1355072230_default.m3u8) 最小為 480×270。現在提供有界的最小短邊 360px，按來源比例計算；舊快照未知比例使用 640px 底限，兩軸仍以 960px 為上限。沒有增加 decoder 或放開碼率／幀率限制。
- `Track.motionArtworkVariants` 保留實際回應中的各版型。方形／圓形封面選 square，首頁直式展示卡按其既有比例選資產；不全域把藝人 fullscreen 改成 square，也不為每張卡增加幾何 layout。原有欄位及舊播放快照保持相容。
- 官方預覽圖模板中的 `{c}` 會展開為 bb；若預覽請求失敗，只再嘗試一次原本的 artworkUrl，再退到 placeholder。相同 owner 更新 URL 時以 lease media ID 重新接上 Texture，避免共用 player 未變就跳過 surface 綁定。
- 可滾動的藝人／通用詳情 hero 只有自身至少 60% 可見時才能成為候選；只儲存跨門檻的 Boolean。原本正常離頁、視窗失焦、低效能及 STOP 的 withdraw/release 路徑保留。

上述 HLS master 僅做無需登入的公開只讀解析；沒有抓取媒體片段。新增 fixture 涵蓋版型／模板解析、最小 rendition 尺寸與上限、快照相容。未操作使用者正在測試的模擬器；本輪沒有量測首 frame、真實 RSS、frame time 或舊裝置 codec 支援率。

## 公開詳情動態封面補齊（11/01）

實際缺口不是缺少播放器，而是資料來源：已保存的 `render-audit/artist-motion-response.json` 為 400，指出 developer credential 沒有 `editorialArtwork` 權限；合併 extension 請求回退後可能完全拿不到動畫。匿名公開詳情卻已明確下發可用影片。`work/apple-music-web/album.html`（SHA-256 `58faf84ecf7cb140dda2e5e539e69a806193f120e665d5a2ea98bd56c2e32fe4`）的 Positions `1553944254` header 含 `videoArtwork.dictionary.motionDetailSquare` 及 `tallVideoArtwork.dictionary.motionDetailTall`。`artist.html`（SHA-256 `4c726679b613c2fda4e3c45bff592eed7a22c70d4b38aeb6d85fdb8322e70815`）的 Ariana `412778295` header 含 square、fullscreen、wide 三種資產。這些是匹配身分的靜態頁面資料證據，並非已實際解碼播放的證明。

`PublicEditorialMotionClient.artwork` 僅為目前開啟的專輯／藝人／歌單補缺少的 motion 欄位，保留所有 Library／佇列／歌曲身分。請求為匿名 `music.apple.com` 公開頁，不帶帳號 token、不執行網頁程式、不調用私有播放端點。限制同來源同 storefront redirect、4 MiB HTML／2 MiB JSON，且頁面 intent、hero resource type／ID、storefront 全部須匹配；不從推薦卡片借用另一張專輯影片。記憶體快取最多 48 筆，可用資產保留 6 小時，真靜態結果保留 15 分鐘，網路失敗不永久記住。沒有逐棚逐卡的網頁爬取。

影片 parser 同時承接 REST `editorialVideo` 與公開頁 `videoArtwork`／`tallVideoArtwork`／`wideVideoArtwork`，預覽尺寸保留來源比例。既有單一靜音 decoder、首 frame 前靜態圖、焦點停留、60% 可見性、生命週期和效能模式守門保留；系統停用動畫時也不播放裝飾影片。新 fixture 驗證頁面／hero／storefront 隔離、快取、失敗重入、取消及 Library 身分保留。整合建置與裝置首 frame 驗證由主 agent 執行，本節本身不宣稱完成裝置驗證。

公開 New／Radio 的棚卡未必附有 `videoArtwork`。`MotionArtwork` 現在透過 `LocalEditorialArtworkLoader` 為真正取得動畫資格的專輯／藝人／歌單卡片補缺少資料；首頁、資料庫及藝人棚在取得焦點時立即查 metadata 並準備影片，不再等待 120ms 或 viewport 停穩。未聚焦／靜態縮圖不建立 loader job，失焦、離頁、選單、生命週期停止及效能模式會撤銷資格並取消工作；共用 client 限制兩個並行讀取、最多四個已啟動工作；UI 取消不丟掉已開始的共享結果，相同資源去重。歌曲的專輯動畫由目前播放項目的另一條接線處理，不對歌曲棚做逐曲請求。只保留同資源的 motion 欄位，既有標籤、歌詞、佇列身分及已顯示的靜態 poster 不被冷載入取代。

## 個人聆聽資料

聆聽紀錄、Heavy Rotation、完整推薦群組及 Replay 使用正式 Apple Music API；來源、分頁、狀態與原生頁面設計另見 [聆聽資料說明](listening-insights.md)。Replay 直接讀取 `/v1/me/music-summaries` 的最新可用期間與所選 top view，不使用私人 Replay 網頁端點、不虛構播放次數或目前年份。


## 動態封面啟動與快取調整（11/01）

先前動態封面每次 focus 後才串接「等待 → 公開 HTML → HLS master → media playlist → video segment → 首幀」。兩個 metadata cache 又把完整網路讀取放在全域 Mutex 裡，另一張專輯／目前播放項目會排在前一個慢頁面後面。現在鎖只保護 cache/in-flight 索引，同一 key 共用 Deferred，不同 key 可兩路並行。背景最多四筆（兩路執行、兩筆等待）；額外 UI 呼叫可取消地等待空位，不產生更多下載工作。取消焦點不讓已下載的 metadata 白費，也不阻止日後相同資源快速命中。網路失敗不當作靜態結果快取。

匿名 motion metadata 記憶體／磁碟各最多 48 筆，使用實際 storefront、語言、資源種類、ID 分隔；成功資產 6 小時、確定靜態 15 分鐘。磁碟僅保存解析後的公開影片／poster 資料，不保存整份 HTML、帳號或 token。跨進程再開不必再讀同一份詳情 HTML。清除或停用時 epoch 使舊請求不能寫回；停用／清除／空間不足尊重既有 ContentCache 設定，設定頁容量及清除涵蓋 metadata 與影片。

`MotionVideoCache` 為單一 Media3 renderer 提供 96 MiB `SimpleCache` + LRU。HLS manifest 每次透過網路更新，只有 initialization/segment bytes 快取，避免固定 master URL 長期指向過期 ladder；progressive 公開影片同樣可命中。cache 初始化在 Media3 loader thread，不在 composition/DPAD 事件執行。每次開流檢查 enabled/clearing/epoch，低磁碟空間保留唯讀快取；寫入中也按 epoch 及 512 KiB 空間檢查停止後續快取。啟播 buffer 250→100ms、rebuffer 500→250ms，buffer 1–5 秒及 4 MiB 上限、960px/30fps/2.5Mbps、唯一靜音 decoder 不變。沒有候選時立即 stop；僅保留 renderer 350ms 供下一張卡片立即重用；這是閒置釋放期限，不會延後新候選準備。

API 依據為 Media3 官方 [SimpleCache](https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache)、[CacheDataSource](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource.Factory)、[HlsDataSourceFactory](https://developer.android.com/reference/androidx/media3/exoplayer/hls/HlsDataSourceFactory) 及 [LoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder)。這是實作與單元回歸說明，實際首幀時間和操作證據由整合模擬器驗證記錄提供。


2026-11-01 過渡修正：原 poster 在可選 metadata 到達時不改成另一張 previewFrame；同影片 lease 的實際首幀後，從固定 alpha0執行320ms淡入，包含已快取的快速首幀。Analytics timeline 的 mediaId 排除舊影片延遲 callback，同 owner 的 URL 也必須匹配。播放器實際首幀及逐幀混合畫面見 `work/ui-polish/20261101-motion-focus/verification.md`。

靜態封面換成影片配對 poster 時，180ms 過渡由 `CrossfadePainter(fadeStart=false)` 保持舊圖不透明、只淡入新圖。冷載不再同時降低兩層透明度露出底色；同步 memory placeholder 仍保留，memory hit 不加過渡。原影片 420ms 淡入、Surface ACK 與單 decoder 保護維持。
