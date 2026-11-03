# Apple Music 功能覆蓋

來源初查：2026-10-28；實作／驗證更新：2026-11-04。這是功能覆蓋與驗證界線，不是全功能完成宣告。原 Apple Music API 目錄的 **138 個 REST 宣告頁**保留在 `work/apple-tv-ui/api-capabilities/endpoint-inventory.json`；本輪另核對 shipped Music Web、真正 Android Music 6.5.3、當前source及專用臨時資源的真服務讀回。早期僅讀文件的紀錄不代表本輪沒有帳號寫入。

「API 已接」表示有可呼叫的服務，仍需 UI、方向鍵、錯誤／空內容與裝置驗證；「欄位可選」不代表每個藝人、地區或開發者憑證都能取得。

本輪建置、短測、寫入／清理及候選hash以 [11/03主工作紀錄](../work/parity-completion/20261103/README.md)為準；早期證據保留在[渲染稽核](../work/apple-tv-ui/render-audit/README.md)。下表「已整合」不代表所有路徑已上機通過；資料來源、UI 焦點與媒體播放需要各自驗證。

## 覆蓋矩陣

| 功能群 | 原廠可提供的資料／動作 | AMATV 目前 | 下一個實作與驗證門檻 |
| --- | --- | --- | --- |
| 帳號、地區、語系 | Developer Token、Music User Token、帳號 storefront、storefront 支援語系 | 已有帳號登入與帳號 storefront；目錄請求使用它 | 語系選擇與地區錯誤保持可恢復；不把登入成功當作播放授權成功 |
| Catalog 搜尋 | 藝人、歌曲、專輯、歌單、MV、電台、Curators、AppleCurators、RecordLabels、Activities，各類 next | `searchPage`、分類 SearchBrowser／原生頁已接；移除 parser 全域 24 筆截斷 | Root 整合後驗證分類、完整 continuation、快速連續查詢與焦點返回；不能播放的 MV／電台依能力隱藏 |
| Library 搜尋 | Library 專輯／藝人／歌曲／歌單／MV，最大每頁 25 筆 | `librarySearchPage`、明確 Apple Music／資料庫 scope、獨立分頁快取已接；保留 Library 身分 | 裝置驗證登入入口、分類切換、上傳未匹配內容與返回位置 |
| 搜尋建議 | Search hints、帶 top-results 的 suggestions | `searchHints/searchSuggestions`、去抖與過期結果取消、真實建議列已接 | 整合後驗證 IME Back／左右游標、建議到分類及結果；顯示 displayTerm、提交 searchTerm |
| 公開新發現 | 公開New／Radio編排、真Room see-all與canonical resources | 匿名標準棚、真Room初頁／續頁及策展者單集棚已接；不再只停在首頁預覽 | 真New→Room100→200及返回／lazy重掛已通過；四個探索Room入口沿既有文字卡承接，其他intent／hero呈現差異另列 |
| Library 瀏覽 | 最近加入、全部專輯／藝人／歌曲／歌單及 MV | 前五者已接分頁；完整Name／Songs與Albums的Artist索引、文字filter／字母定位已接；不可播MV依能力隱藏 | 裝置驗證空內容、續頁與上傳未匹配內容，不偽造 catalog ID |
| Library 歌單資料夾 | root／children／parent、建立與受支援的管理 | 真根目錄More、建立首個資料夾／歌單、children／parent及來源返回已接；管理逐項設gate | 分開驗證大量本地fixture、真帳號root建立權限與刪除／移動；canEdit不代表children-create權限 |
| Catalog 藝人 | Profile、12 種公開 views、專輯／MV／歌單／station 關聯 | `artistProfile`、`artistSectionPage`、原生詳頁與混合返回堆疊已整合 | 分段 lazy 載入、每段 next／retry、空段移除、方向鍵返回原段 |
| Library 藝人 | 儲存的藝人與其 Library 專輯，及可選 catalog 映射 | 原始 r.* 身分與 catalog 藝人分開；無映射時只提供 LibraryAlbums | 不把「我的專輯」替換成全部目錄專輯；切入目錄使用真實映射 |
| 藝人完整介紹 | 公開 editorialNotes；網頁額外 artistBio、origin、bornOrFormed、isGroup | 已核對出貨網頁請求並加入可選 extension；不支援時回退公開 profile | 實際有回傳才顯示；介紹與 genre/出生或成團／故鄉需要可滾動詳情 |
| 動態封面 | 出貨網頁 editorialVideo/extended assets 的方形、直式、寬幅、藝人 fullscreen 等資產 | 各版型 parser、每頁 scoped metadata、單播放器／焦點／自身可見性／生命週期已整合 | 先保證焦點／可見性／生命週期，限制同時解碼；無動態資產用真封面 |
| 專輯 | metadata、曲目（可含 MV）、藝人、曲風、record-labels；另有專輯 views、UPC／equivalence | 曲目逐頁、真藝人及 `catalogPages` 完整 metadata／關聯／views API 已接 | 漸進載入 UI 整合驗證、Label/Genre 等連結與回到原歌曲；AudioVariants 僅表示目錄可提供版本 |
| 歌曲 | metadata、albums／composers／genres、真credits | 一般及Queue資訊、真專輯捷徑；optional web credits補演出／詞曲／製作工程角色 | 真公開credits已讀回，身份／optional拒絕fixture已接；畫面與長名單返回按裝置證據判定 |
| 音質標示 | audioVariants（Atmos、Dolby Audio、lossless、hi-res、lossy）、Apple Digital Master 等 metadata | 資訊頁已格式化呈現伺服器回傳的目錄 audioVariants | 目錄「有此版本」與當下實際播放格式分開；不以 metadata 宣稱音訊管線已輸出 Atmos/lossless |
| 歌單 | metadata、tracks、curator／related views、Library可編輯性 | 逐頁瀏覽；普通Play交continuation；可靠raw total時可均勻sample先Shuffle，完整tail背景驗證後發布 | 沒有total／不能驗證offset才完整載入fallback；大歌單真首播與tail恢復仍需對應短測 |
| 加入資料庫 | POST `/v1/me/library?ids[type]=...` | 選單按 nullable inLibrary 隱藏已加入項目，提交後讀回狀態；未確認則維持請求已送出 | 真帳號寫入須由使用者明確操作；202 是接受請求，讀回關聯才能確認已加入 |
| 喜好項目 | POST／DELETE favorites、canonical inFavorites讀回 | 加入／取消與真讀回已接；favorite-library-02的四項寫入及cleanup通過，取消喜好已啟用 | 不用舊Library flag誤認確認，不以DELETE rating代替取消喜好；UI／服務驗收分開 |
| 喜歡／減少推薦 | GET／PUT／DELETE catalog 或 Library ratings，value 1／-1 | `rating/setRating/deleteRating`、目前狀態與選單已整合 | 使用者明確操作後的真帳號結果與刷新；藝人沒有被列入 rating 類型 |
| 建立／加入歌單 | create／append、parent folder與重複項目 | 真臨時歌單create／append已讀回完整順序；unknown結果以GET恢復、不重送POST | 建立／追加成功與後續可見延遲分開；root建立UI及兩個Library連動偏好仍有獨立驗收 |
| 修改／刪除／重排歌單 | shipped web的PATCH／PUT／DELETE及membership | 正確web Origin transport、既有sheet管理pane；改名／重排已真讀回；刪除以tombstone＋parent/root消失確認 | 臨時資源cleanup已確認；資料夾改名／移動已在management-remaining-01通過並啟用，production gate與UI驗收分別記錄 |
| 個人推薦 | recommendation root／contents及巢狀群組next | Home棚標頭進既有Insights，保留精確group ID與Back語義；Insights root只保留群組metadata，開啟群組才讀contents並補齊當頁資源metadata | 保留server標題／reason、獨立重試及完整cursor；原網頁私人首頁功能不全等於公共API |
| 歷史、常聽 | 最近資源／歌曲／電台、Heavy Rotation | Home顯示全部已接accountInsights來源，完整分頁與空／錯誤頁可達 | 快鍵／來源切換與Back有短測；不把停止讀歷史當停止Apple記錄播放 |
| Replay | 公共music-summaries最新summary及所選top view | Home既有標頭進Replay，summary／view及無資料狀態沿同一Insights頁 | 不捏造年份／次數／分鐘；私人all-time-insights不算公共Replay已覆蓋 |
| 曲風、排行榜 | 曲風清單、依曲風榜單、歌曲／專輯／歌單榜、cityCharts／dailyGlobalTopCharts 真實歌單 | 新發現外頁直接接入五類排行榜及所有類型，genre charts／原生深頁仍保留 | 待裝置驗證整合後上下導航、讀取中焦點交接、空內容／失敗及真實續頁；公開 editorial 區段不取代完整 chart cursor |
| Activities、Curators、AppleCurators | 真實資源與歌單關聯 | 分類搜尋與真關聯開啟資訊頁、歌單關聯及 lazy 分頁已整合 | 裝置驗證真 ID 導航與續頁，不能把介紹文字偽裝成頁面 |
| Record Labels | 唱片公司資源及 relationship views | 從專輯真實 record-labels 關聯開啟資訊頁與 latest-releases/top-releases 已整合 | 裝置驗證發行作品／返回原專輯 |
| 電台 | live／personal／recent stations、station-genres、節目及單集 | 官方JS station引擎、Radio曲風入口、真radio-show→策展者單集Room；直播Stop與有限單集游標恢復 | AppleMusic1既有實證、Hits本輪transport／recreate／FFT通過；單集FFT／真斷線等依最新短測分別列示 |
| MV | Catalog/Library MV、views、4K/HDR 等欄位、預覽 | 搜尋／metadata 有；SDK bridge 目前拒絕正式 MV 播放 | 影片播放／Surface、遙控器、音軌與返回、真正授權能力；可選導航不代表刪掉底層功能研究 |
| 播放、佇列、接續播放 | MusicKit queue、seek、repeat/shuffle、能力回報；MusicKit JS 另有 MV/station options | 原生音樂流程已有；選單已接 app queue `enqueueNext/enqueueLast`，不重啟目前歌曲；載入後核對原 session | 整合驗證手動 Next／Last、重複項目、shuffle／repeat；metadata 支援不能證明 SDK 所有媒體類型可播 |
| 歌詞、Sing 與其他原廠整合 | TTML、翻譯／發音、原生Larger Text；部分能力不在REST | 原歌詞動畫保留，較大歌詞／發音二選一已接、預設不變；Sing無真能力仍隱藏 | 字級持久化／重新量測獨立驗證；不將TTML或策展歌單當Sing人聲分離 |

## 公開新發現資料契約

2026-10-30 以無 Cookie／無 token 的公開 GET `https://music.apple.com/tw/new` 取得完整 server models。原 HTML SHA-256：`eb34c8d395195e8320b6d47167b4b383b5929e684f7bf1c5492dbe843dcdcef5`；原件在 `work/apple-music-web/new-page.html`／`new-page-data.json`。這是 **Apple Music 公開網站** 證據，與 Android Apple TV 影片 APK、使用者 tvOS 照片分開；它沒有證明 tvOS 採用同一首頁版型，也不是借用網站內部憑證的私有 grouping API。

`MusicServices.browseShelves()` 使用 `PublicMusicBrowseClient`。未登入時採 BuildConfig storefront；有 Music User Token 時先取得實際帳號 storefront，但公開 `/new` GET 不轉傳 User／Developer Token。語言由目前 App locale 的 Accept-Language 指定；網路讀取在 IO、解析在 Default dispatcher，取消可傳遞。讀取上限 **4 MiB**，10 秒連線／讀取逾時，限制重新導向至同一 HTTPS `music.apple.com` storefront 路徑；無內容／壞 schema 顯示既有可恢復的 unavailable／retry，不默默拿 charts 當新發行。

`PublicMusicBrowsePage` 只讀唯一的 `script[type=application/json][id=serialized-server-data]`，JSON 上限 **2 MiB**，兩個大小皆按 UTF-8 bytes 驗證。只選一個符合請求 storefront 的 `BrowsePageIntent`，sections 限 1–128，每棚最多 512 項；缺必要 schema、地區不符或沒有可用標準棚會回明確 parse failure，不退到別國或不相關頁。它不執行 JS、playAction、segue actions、metrics，也不使用 userTokenHash。

輸出只取有真實 header title 的標準 shelf，沿用來源順序；`title/titleLinks`、`artistName/subtitleLinks` 與 `contentDescriptor.kind/identifiers.storeAdamID` 分別提供顯示文字及真實 resource type／ID。未知時長為 0，畫面不得顯示成已知的 0:00；沒有製造歌曲、artist、room 或 playlist ID。公開單一背景色重複成兩個相同 palette 色值，滿足既有 Artwork gradient／painter 的至少兩色契約，沒有捏造額外色彩。所有標準棚 `featured=false`；station／music-video 再依 presentation capability 過濾，不把不能播放的項目留成入口。

圖片僅接受 HTTPS `*.mzstatic.com/image/`。有方形 dictionary 時優先使用受限的 640px template，避免把網頁 compact track row 的 48／96px srcset 放大到電視；若 dictionary 是寬畫布，必須在同一公開 HTML 找到同 template 已實際呈現的方形 rendition 才加入，不自行把 4:1 banner 改成方形。無標題flowcase hero不改造成另一套首頁；RoomPageIntent see-all保留真id／storefront／canonical URL，進專用public-rooms route，不偽裝成公共catalog rooms端點。link／grouping等不同intent依其真schema承接，不能只用標題造路由。

縮減 fixture `app/src/test/resources/apple-music-public-new.html` 保留原 14 個 sections 及 canonical 顯示欄位，移除追蹤／播放指令。基本能力輸出 **10 棚、170 項**：新歌精選 17、本週新發行 20、近期推出 20、歌單已更新 20、季節精選 19、現正流行 20、樂迷熱播 20、每日 Top 100 7、城市排行榜 7、即將推出 20。電台可用時另有 20 個真實節目。Daily／City 只預先呈現七個方形封面，其餘 wide-only 項目沒有擅自裁圖；這不是完整榜單數量，也不寫死台灣內容 ID。即時回應會隨時間／storefront 改變。

四項 parser fixture 測試已納入 10/30 整合基線；它們證明資料解析規則，不能代替新發現頁渲染、方向鍵、網路失敗／空內容及開啟資源後返回的實測。完整榜單直接接新發現共享lazy shelves，使用API真continuation；Room see-all已接既有resource頁，保留SSR first page及editorial next，不再只顯示原預覽。寬幅hero的editorial呈現與可用的Album／Playlist／Curator路由分開判定。

## 藝人服務契約

- `artistProfile(Track): ArtistProfile` 保留 `artist` 原始身分，另提供 `catalogArtist`。Library 藝人只在 Apple 明確映射時取得後者。
- `ArtistProfile.applicableSections` 是有文件的可查區段，不是該藝人每段都有內容的保證。LibraryAlbums 使用原 Library ID；其餘 catalog 區段使用真正 catalog artist。
- `latest-release` 的頁數上限為 1。NewJeans（1635469693、tw）原本 limit=10 實際回 HTTP400／40005；修正後為正常空關聯 HTTP404／40403。只有已驗證關聯／view 請求中的明確 40403 轉為空資料，沒有內容的區段正常略過；未知404、缺少資源、授權、伺服器及網路錯誤仍保留錯誤／重試。原始公開 Catalog GET 證據見渲染稽核 `130-artist-readonly-*`，沒有使用 User Token 或記錄認證 headers。
- Artist controller 在取消後拒收晚到錯誤；空頁若還有 cursor 保留載入／續頁錨點，終端空區段移除時安全交接焦點，離頁或新的遙控意圖取消舊交接。
- 公開 views：appears-on-albums、compilation-albums、featured-albums、featured-music-videos、featured-playlists、full-albums、latest-release、live-albums、similar-artists、singles、top-music-videos、top-songs。
- 公開關聯：albums、genres、music-videos、playlists、station。`genreNames` 作為 profile metadata；其餘可播放／可瀏覽內容由 `artistSectionPage` 分頁。
- `relatedArtistsPage(item)` 只跟隨專輯／歌曲／MV 的真實 artist 關聯，Library 身分和 token 規則不變。
- `searchPage/librarySearchPage` 保留類型及 next；沒有該類搜尋結果是空列表，不是整個搜尋失敗。

## 寫入服務契約與確認界線

`MusicServices.catalogActions` 共用現有 authenticated transport，不再為每個功能另建 HTTP client。API 請求固定於 `https://api.music.apple.com/v1/`、拒絕重新導向，保留 user/developer token 的既有錯誤分類；空的 202／204 回應不再被當作 JSON 解析失敗。

`availableActions` 依真實 resource type/ID 及明確 catalog 映射決定能力，`readState` 的 null 代表服務沒有回報；不把 null 當 false。可加入的 catalog 資源會以 `include=library` 讀取真正 Library 關聯作為加入後的確認，無此回報不製造已加入狀態。收藏使用 catalog ID，個人評價與 Library 歌單曲目使用原始 Library ID。建立與追加的本機 fixture 驗證 JSON 結構／身分／重複項目，本身不發帳號請求。先前真機按鍵交接曾意外觸發加入喜好，事件與後續只驗證導航／不自動啟動選單操作的界線見 [渲染稽核](../work/apple-tv-ui/render-audit/README.md)；不能把 fixture 無副作用擴大成整個開發過程沒有帳號變更。

建立／追加／改名／重排已在本輪唯一命名臨時資源上真寫入及讀回，保留重複occurrences；先前省略web bearer要求Origin的401已找到差異。刪除後GET仍可回tombstone，需同時核對metadata與原parent/root完整membership，不能只等404。取消喜好、有明確catalog映射歌曲移出Library的favorite-library-02四項寫入已通過且cleanup=true；資料夾改名／移動也已通過並啟用。個別production能力gate與UI驗收見[管理追查](../work/parity-completion/20261103/cloud-management.md)及主工作紀錄；不能把部分成功寫成全管理完成。逾時create／append不自動重送。

## 證據與限制

主要來源：[API 總目錄](https://developer.apple.com/documentation/applemusicapi)、[藝人屬性](https://developer.apple.com/documentation/applemusicapi/artists/attributes-data.dictionary)、[藝人 views](https://developer.apple.com/documentation/applemusicapi/artists/views-data.dictionary)、[藝人關聯](https://developer.apple.com/documentation/applemusicapi/artists/relationships-data.dictionary)、[搜尋](https://developer.apple.com/documentation/applemusicapi/search)、[Library 歌單](https://developer.apple.com/documentation/applemusicapi/playlists-api)、[喜好項目](https://developer.apple.com/documentation/applemusicapi/add-resource-to-favorites)、[個人評價](https://developer.apple.com/documentation/applemusicapi/ratings-api)、[加入資料庫](https://developer.apple.com/documentation/applemusicapi/add-a-resource-to-a-library)、[Replay](https://developer.apple.com/documentation/applemusicapi/get-the-user's-replay-data)、[歷史](https://developer.apple.com/documentation/applemusicapi/history)、[MusicKit JS](https://js-cdn.music.apple.com/musickit/v3/docs/).

網頁可選 extension 的出貨程式證據：`work/apple-music-web/source/index.js`，SHA-256 `752cc36a456f41e8b648791ea1f890a0d8e4adef86c9bfc5409018da7ca94a93`。藝人主請求位於字元 offset 585928，介紹請求約 565300；motion mapping 約 557659–558427、2007053–2007480。這些是實際出貨程式中的請求／欄位，不是所有憑證都成功回傳欄位的實測。`more-to-hear/more-to-see/canvas` 等網頁 view 也不能直接列成已支援的公開 REST ArtistSection。

138 個 REST 宣告是目前主題索引的 endpoint coverage，並不代表所有 resource metadata 已在 UI 呈現；各資源屬性、服務端版型、身份授權和播放媒體能力要分開核對。這份矩陣中未完成的列仍是完整 App 目標的一部分。

## 通用資源詳情服務

`MusicServices.catalogPages` 共用現有 transport、同一個受限 JSON parse dispatcher 及 Track parser，不建立另一個 HTTP client。`profile(ref)` 只取得一個資源及必要的 Library/catalog metadata；`sectionPage(ref,descriptor,next)` 每次只取得使用者接近的一頁，並驗證續頁原點、地區及完整端點。曲風榜單還驗證 genre/type 不被另一個分頁替換。

`CatalogResourceRef(id,type)` 包含 namespace，不能只以數字或標題當身分。新增單一 `CatalogKind.Page` 表示 curators、apple-curators、record-labels、activities、genres、station-genres 與 library-playlist-folders，避免每個頁面類型擴成播放器種類。`CatalogActions.availableActions(Page)` 一律 false，不把這些頁面當歌曲或歌單寫入。

Profile 提供真封面／動態封面／canonical URL、可格式化 metadata、服務端標題、可查的關聯／views、真實 parent genre link，以及 nullable inFavorites/inLibrary/canEdit。Metadata 包含發行日、版權、唱片公司、曲數、分級、曲風、目錄 audioVariants、作曲者文字、作品／樂章、光碟與曲序、ISRC/UPC、影片／電台／策展／Library 狀態等實際回傳值。Catalog 可選 extensions 被拒時只回退公開基礎 profile，不製造缺少資料。

文件交叉核對的細節：Album `appears-on` view 回傳 Playlists；Song `composers` 回傳 Artists；RecordLabel 只有公開 `latest-releases/top-releases` views；Station `radio-show` 回傳 AppleCurators；Genres 沒有公開 relationship 字典，使用 `charts?genre=<真實 genre ID>` 呈現歌曲／專輯／歌單／MV 榜單；StationGenres 有真正 stations 關聯。Library folder 的 children 與 parent 都保留 Library namespace。

真正的 Sing 策展頁目前在出貨資料中是 `apple-curators/1710452798`，不能因網站 URL 使用 `/curator/` 就猜成 `curators`。它只提供普通歌單內容，不證明 Sing 人聲分離能力；依使用者最新指示，不能運作的原生功能入口應由 presentation capability 隱藏，API 資料保留供查核與未來可用的實作。

新增 fixture 覆蓋完整 metadata/canonical URL、個人歌單可編輯性與 catalog enrichment、AppleCurator namespace 和續頁、Genre parent／榜單與跨 genre cursor 拒絕。這些是必要的資料正確性檢查，不代表 UI／裝置／播放驗證已完成。主要來源：[Albums](https://developer.apple.com/documentation/applemusicapi/albums)、[Songs](https://developer.apple.com/documentation/applemusicapi/songs)、[Playlists](https://developer.apple.com/documentation/applemusicapi/playlists)、[Curators](https://developer.apple.com/documentation/applemusicapi/curators)、[AppleCurators](https://developer.apple.com/documentation/applemusicapi/applecurators)、[RecordLabels](https://developer.apple.com/documentation/applemusicapi/recordlabels)、[Activities](https://developer.apple.com/documentation/applemusicapi/activities)、[Genres](https://developer.apple.com/documentation/applemusicapi/genres)、[StationGenres](https://developer.apple.com/documentation/applemusicapi/stationgenres)、[Charts](https://developer.apple.com/documentation/applemusicapi/charts)。

## 分類搜尋與遙控器契約

`SearchBrowser` 只請求目前 query／scope／category；每類每頁 25 筆，接近可見列表底部才請求 next。切換類型保留同 query 的列表與捲動位置，新 query 清掉舊快取並取消舊請求；暫時離開搜尋會取消 debounce／請求／建議，但保留已取得內容。重新顯示只恢復尚未取得的目前類型，不重新載入所有類型。Continuation 必須符合原 API origin、storefront／Library path、完整 term 與唯一 types，拒絕跨查詢、跨分類、跨帳號 namespace 的 cursor。

Hints 使用 `/v1/catalog/{sf}/search/hints`；兩字以上以 `/search/suggestions?kinds=terms,topResults` 優先取得真實詞語與資源，不支援該端點時才回退 hints。詞語使用服務端 `displayTerm` 顯示、`searchTerm` 提交，避免把遮蔽文字還原到畫面。建議失敗不覆蓋正式搜尋結果，Library 搜尋不發 Catalog 建議請求。缺圖的 Library 藝人使用一次批次 artists lookup 加上 `include=catalog`，保留原始 r.* ID、名稱及排序；不對每張卡片發請求。

方向鍵路徑：輸入欄下 → 可用建議列 → scope → 水平所選分類 → Down 進入該類上次焦點結果；分類左右切相鄰類型、Up 回 scope，空類型留在分類，失敗進入重試。結果第一列 Up 揭露當前分類，第一欄 Left 回全域導航。輸入模式保留原生左右游標；IME 自行消耗 Back 時，以已觀察 visible→hidden 結束 editing，初始未 show 的 hidden 不算取消、外部語音失焦期間延後判斷。field 實際收到的 Back-down 仍關閉 IME 並消耗對應 key-up，下一次 Back 才由頁面導航處理。已移動方向／切換 scope／query 時，取消等待中的「資料回來後進入結果」焦點意圖。這些行為需 Root 整合後做裝置驗證；資料與取消 fixture 不等於焦點或效能實測。

來源：[Catalog Search](https://developer.apple.com/documentation/applemusicapi/search-for-catalog-resources-(by-type))、[Library Search](https://developer.apple.com/documentation/applemusicapi/search-for-library-resources)、[Search Hints](https://developer.apple.com/documentation/applemusicapi/get-catalog-search-hints)、[Search Suggestions](https://developer.apple.com/documentation/applemusicapi/get-catalog-search-suggesions)、[TermSuggestion](https://developer.apple.com/documentation/applemusicapi/searchsuggestionsresponse/results-data.dictionary/termsuggestion)、[Multiple Library Artists](https://developer.apple.com/documentation/applemusicapi/get-multiple-library-artists)。Insights 的詳細證據與限制見 [listening-insights.md](listening-insights.md)。

## 專輯／歌單漸進載入

`collectionTracksPage` 只解析原始 collection 的直接 tracks 關聯、保留重複歌曲及 Library resource 身分，驗證每次 next 原點、storefront／Library namespace 與整個關聯端點。一般瀏覽先顯示第一頁，只有接近底部或使用者按更多才續頁。State 的 `total` 來自服務端（可能包含不可播放的非歌曲資源），`tracks.size` 只是目前歌曲數；只有 `complete` 為 true 才能把本機內容當成完整可播放清單。

一般Play沿用已載頁面立即移交同一runtime的continuation，未完成原context前不誤接autoplay。Shuffle在可靠raw total時先均勻抽取offset occurrence，驗證total／raw count／next與位置後首播；完整source人口在既有continuation背景讀完，再一次發布尾段。無可靠total或sample不能驗證時保守fallback；保留同一次raw draw避免條件偏差。Catalog playlist tracks上限300、Library上限100；server cursor與明確limit照原值保留。重複歌曲、取消、Next／Last、關Shuffle及checkpoint恢復由同一queue owner處理。完整語義與snapshot-token限制見[大型歌單](../work/parity-completion/20261103/large-playlist.md)。`collectionTracks`仍供明確需要全內容的enqueue／append使用，沒有第二套parser。

## 完整公共排行榜

`MusicServices.catalogCharts.page(category,cursor)` 一次請求歌曲、專輯、歌單、城市榜歌單或每日百大歌單中的一類一頁（25 筆）。保留 Apple 回傳的 localized name、chart、orderId、href、next 與可選 total；不從已載入數量製造完整總數，也不建構榜單歌單 ID。City／DailyGlobal 使用官方 `types=playlists&with=cityCharts`／`dailyGlobalTopCharts`，由對應 results group 讀取真實 Playlists。沒有回傳該區段就顯示空內容，沒有可播放 MV 入口。

Opaque `CatalogChartCursor` 綁定 category、帳號 storefront、原始 chart、可選 genre／伺服器 chartId 與原端點；拒絕外站、跨地區、非 charts 路徑、重複／不同 types 或 chart 的續頁。New外頁以同頁棚列呈現五類排行榜與所有類型，共用既有卡片、選單及More／Retry；各棚初頁、獨立續頁與取消規則見[下節整合契約](#新發現外頁整合類型與排行榜1101-整合)。沒有獨立Charts輔助頁／browser或分類切換側欄。

來源：[Charts endpoint](https://developer.apple.com/documentation/applemusicapi/charts)、[Chart playlist fields](https://developer.apple.com/documentation/applemusicapi/chartresponse/results-data.dictionary/playlistschart)、[WWDC21 chart playlist request](https://developer.apple.com/videos/play/wwdc2021/10291/?time=419)。出貨網頁 `index.js` 的 `_le`／`M3`（字元 offset 461832／466794，同上 SHA-256）也使用 results.cityCharts／results.dailyGlobalTopCharts 及各 group 自己的 next。`filter[storefront-chart]` 的公共單國榜單查詢有文件，但這輪沒有另外做跨國 storefront picker。

City／Daily Global 實際 HTTP 200 回應的 `href/next` 使用 `chartId`，不重複 `types/chart/with`。解析器保留這種原始 cursor，將第一個由該回應提供的 ID 綁定後驗證後續連結；不把原本缺少 types 的合法回應誤判為錯誤，也不硬編碼榜單 ID。

Search／Library catalog enrichment／Artist section／generic section／Insights／Charts 已補每頁 scoped `extend[type]=editorialVideo`，可選參數遭 400／403 拒絕時回退原始頁請求，不逐卡請求。這只向服務索取可選 metadata；真回應沒有 motion 資產就保持靜態，不能宣稱這些端點或所有卡片必定提供動畫。

### 新發現外頁整合類型與排行榜（11/01 整合）

移除固定在頂端的「所有類型／排行榜」兩顆入口。公開 New editorial shelves 維持原順序、先顯示；其後直接呈現 Top Songs、Top Albums、Top Playlists、City Charts、Daily Top 100、所有類型。五個榜單各讀真實 `CatalogChartsApi.page`，類型讀 `CatalogPagesApi.rootPage("genres")`；每頁 25 項，後續頁只採用伺服器 cursor。類型沿用原 root 頁 190×76dp 的文字卡，不製造不存在的 artwork 或 hero。榜單使用同一外頁controller；曲風詳頁仍承接真genre資源，未接入口的獨立Charts輔助頁已移除。

`BrowseInlineBrowser` 在 New 的 editorial 可顯示後才開始獨立補齊；最多同時兩個讀取，既有 New 不等待這六項。六個 shelf slot／key 從第一幀即固定，網路完成順序不插入或移動別排；空結果保留 reload slot，單項失敗只更新本排。載入 slot 已聚焦時，結果到達會把焦點交給原位置的第一個真實內容。隱藏、進入 detail／選單或切 tab 即取消尚未完成讀取；generation 與實際 job identity 排除舊回應，已完成內容／cursor 保留；帳號 session 清理跟隨現有 Host。

新增三項 controller 測試覆蓋兩讀取上限與失敗隔離、取消後舊結果不可覆蓋、續頁失敗保留資料並重用同一 cursor。三項測試已隨 125 整合的 447 項單元測試通過；實際 API／遙控器呈現的驗證與限制由渲染稽核 126 後續記錄。

## 11/03 補上的原生操作

- 分享／複製canonical URL：共用More與Queue選單，系統分享不可用時沿用QR；沒有網址猜測或新分享框架。
- 顯式歌曲電台：選定seed原子進入既有AppleMusic推薦流程，不把catalog直播station當歌曲seed。
- 睡眠計時：Off、15／30／45／60分鐘、本曲與本專輯／歌單結束，沿既有runtime與container occurrence來源；不新設常駐服務。
- 系統EQ：有效native song session且裝置有系統面板才顯示，點擊時重驗；Station不沿用舊session。
- 加資料庫偏好：favorite／playlist主操作確認後才做可選Library新增，預設關閉；部分失敗只恢復未確認Library步驟，不重送已成功主要寫入。

真正Android Music6.5.3與shipped web證據、各項可達路徑及SDK限制見[parity ledger](../work/parity-completion/20261103/parity-ledger.md)。音訊Crossfade、Sound Check、Lossless／Atmos真輸出、受保護離線下載及Casting仍無目前SDK可用的完整正式路徑，不用metadata或原App資源宣稱可用。B6完整862項unit與最後41項mutation測試通過；正式standalone已建置並保留資料安裝。最後模擬器timer p95 18.096ms、max 33.034ms、零樣本超45ms，不代表實體TV穩定60fps。
