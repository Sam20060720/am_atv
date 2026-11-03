# Library 資料來源與分頁

資料來源初查：2026-10-27；實作與驗證更新：2026-11-04。先查 Context7 `/websites/developer_apple_applemusicapi`，再核對 Apple Developer 的端點、資源模型及分頁文件。這些瀏覽資料由 `AppleMusicApi` 的 REST 請求提供，使用既有 developer token 與 Music User Token；不需要更換 Android 播放 SDK。

## 本次接上的內容

| 分類 | 官方端點 | AMATV 方法 | 顯示與載入規則 |
| --- | --- | --- | --- |
| 最近加入 | `/v1/me/library/recently-added` | `recentlyAddedPage(nextPage)` | 保留混合的 library 資源及回傳順序，不再固定截成 24 筆；依伺服器 `next` 載入 |
| 專輯 | `/v1/me/library/albums` | `libraryAlbumsPage(nextPage)` | 使用者資料庫的專輯，官方按字母排序 |
| 藝人 | `/v1/me/library/artists` | `libraryArtistsPage(nextPage)` | 官方按字母排序；`include=catalog` 可補目錄中的藝人圖片，未對應時仍顯示姓名 |
| 播放列表選擇器 | `/v1/me/library/playlists` | `libraryPlaylistsPage(nextPage)` | 用於追加目標等平面列表，保留個人身分，不以catalog ID取代 |
| 播放列表根目錄 | `/v1/me/library/playlist-folders/p.playlistsroot/children` | `catalogPages.sectionPage(root, children, next)` | Library實際分類入口，保留真資料夾／歌單階層及parent返回 |
| 歌曲 | `/v1/me/library/songs` | `librarySongsPage(nextPage)` | 官方按字母排序；同一 catalog song 的不同 library entries 仍保留 |
| 藝人的專輯 | `/v1/me/library/artists/{libraryArtistId}/albums` | `libraryArtistAlbumsPage(artist, nextPage)` | 只列該藝人在使用者資料庫內的專輯，不擴成所有目錄專輯 |

`artistAlbumsPage(artist, nextPage)` 依原始資源類型分派：Library 藝人使用上述 Library 關聯，catalog 藝人使用帳號 storefront 的 `/v1/catalog/{storefront}/artists/{id}/albums`，不把藝人當作歌曲播放。[Catalog 藝人關聯文件](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-5akdm)列有 `albums` relationship。

專輯、藝人、歌曲及播放列表每次請求 100 筆，追加 `include=catalog` 作為原條目的 metadata。最近加入的公開文件僅列 localization 參數，因此不自行加 `limit` 或 `include`。一般瀏覽每次只解析一頁並回傳 `LibraryPage`，由畫面決定下一頁；只有使用者明確要求完整排序／篩選／字母索引時，`LibraryBrowseTools`才可取消地收集該分類完整資料並在worker建索引，不能把首100筆當全部。

`LibraryPage.total` 取自伺服器可選的 `meta.total`，沒有回傳時為 `null`。`items.size` 只代表目前這一頁，不能當作完整資料庫數量。

來源：[最近加入](https://developer.apple.com/documentation/applemusicapi/get-recently-added-resources)、[所有資料庫專輯](https://developer.apple.com/documentation/applemusicapi/get-all-library-albums)、[所有資料庫藝人](https://developer.apple.com/documentation/applemusicapi/get-all-library-artists)、[所有資料庫播放列表](https://developer.apple.com/documentation/applemusicapi/get-all-library-playlists)、[所有資料庫歌曲](https://developer.apple.com/documentation/applemusicapi/get-all-library-songs)、[資料庫藝人關聯](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-9dsoc)。

## 身分與分頁

- Library 藝人保留 `resourceType=library-artists` 及原始 `r.*` 的 `resourceId`。關聯端點使用這個身分，不能使用目錄藝人 ID。新 `CatalogKind.Artist` 代表可瀏覽的藝人，不送入歌曲播放佇列。
- Library 藝人的公開屬性是 `name` 及可選 `inFavorites`，沒有必須存在的圖片。圖片取自可選的 catalog relationship；無圖時交由 UI 顯示佔位圖示，不製造白色圖片，也不逐一額外查詢每位藝人。
- 只解析根 `data` 中符合分類的項目。內嵌的 catalog relationship 只用來補 metadata，不額外長出一張卡片。
- 下一頁保留完整的伺服器 cursor/query，包括編碼、語系等參數。只允許同一個 `https://api.music.apple.com` 端點；藝人關聯的 cursor 也不能切換到另一位藝人。
- 依 library resource type + ID 合併頁面；不因 catalog ID 相同而刪除使用者的獨立資料庫條目。重複回傳同一個 cursor 會明確失敗，避免假裝已載完。
- 保留伺服器提供的上傳／未匹配 Library 條目，包括 `i.*`、`l.z-*`。缺少封面保留 `null`、缺少 artist 留空；真缺名稱時顯示資源類型。沒有明確 catalog 對應時 `catalogId=null`，原始 Library 身分仍在 `resourceId` / `resourceType`。點選歌曲後才以 library song ID 查找可播放對應，無對應時明確回報不可用，不把 Library ID 假裝成 catalog ID。
- `catalogId=null` 也不代表展示內容。播放、暫停、恢復與佇列入口只把 `MockCatalog` 中的實際展示歌曲視為 demo；未匹配 Library 歌曲照常進行解析，不顯示假的播放進度、不混入展示佇列。
- 缺少時長使用 `0` 表示未知；只有回傳正值或明確 catalog 映射的真實時長才能補值，不再填入 48 秒。原生拖曳能力另以已準備的 SDK `canSeek()` 與已知時長判定，不從缺失 metadata 推定可拖曳。

來源：[分頁](https://developer.apple.com/documentation/applemusicapi/fetching-resources-by-page)、[資源與關聯](https://developer.apple.com/documentation/applemusicapi/handling-resource-representation-and-relationships)、[LibraryArtists 屬性](https://developer.apple.com/documentation/applemusicapi/libraryartists/attributes-data.dictionary)、[LibraryArtists 關聯](https://developer.apple.com/documentation/applemusicapi/libraryartists/relationships-data.dictionary)。

## API 還能提供什麼

目前已接 [Library 搜尋](https://developer.apple.com/documentation/applemusicapi/search-for-library-resources)，可分別搜尋專輯、藝人、歌曲、播放列表與音樂影片，各類結果有自己的 `next`；最大每頁 25 筆。這和目前全站目錄搜尋是兩個端點，不能用已載入的前 100 筆在本機篩選來冒充完整 Library 搜尋。

也有 [所有 Library 音樂影片](https://developer.apple.com/documentation/applemusicapi/get-all-library-music-videos)。目前Android MusicKit AAR未提供正式MV Surface／Sing路徑，相關入口依實際能力隱藏；Radio已經由另一條官方MusicKit JS station adapter接入，不再列為一律隱藏。歌曲種子電台、直播與有限單集身分分開。

專輯與歌曲的 `genreNames` 可用於本機分類；本次核對的 [Music Genres](https://developer.apple.com/documentation/applemusicapi/music-genres) 文件列的是 catalog genres，未列 `/me/library/genres`。Library 專輯／藝人／歌曲的列表端點也未列可選的 `sort` 或 genre filter，因此本次不發送未記載的查詢參數。目前已實作原始順序／名稱排序、歌曲與專輯的藝人排序、文字篩選與字母定位，先收齊所選分類再建本機索引；切tab／重建保存已套用選項及捲位，保留來源identity，不保存第二份原始資料。藝人排序沿同一完整index worker，按artist→title→原source順序，字母列使用artist初字。其他排序只有真欄位／來源證據到位才加入，不能依顯示文字猜最近播放或缺失日期。

最初只接最近加入、播放列表／歌曲及固定截斷的限制已處理；不能沿用它判斷目前分類仍未實作，也不能推論帳號Library很小。真帳號內容、可編輯性與播放能力仍需分開確認。

## 根目錄、管理與實證

Library Playlists工具列的More使用真正 `library-playlist-folders:p.playlistsroot`，沿既有CatalogActionSheet建立歌單／首個資料夾；即使分類為空仍有入口。folder本身canEdit、canDelete與children建立權限不是同一件事，不能把某一個false套用到所有操作；明確服務拒絕只關閉對應動作。

本輪管理共用既有web bearer／Origin transport及同一sheet子pane。專用臨時歌單的create／append／rename／完整occurrence reorder已真讀回；刪除後服務可保留GET200 tombstone，因此以fresh metadata及原parent／root完整membership消失確認，臨時歌單／空資料夾cleanup已有證據。資料夾改名／移動已在management-remaining-01通過並啟用；favorite-library-02四項寫入及cleanup通過，取消喜好及有明確catalog映射的歌曲移出Library也已啟用。production gate／服務讀回／UI驗收分開，不把API或enum存在當成全部完成。詳見[管理紀錄](../work/parity-completion/20261103/cloud-management.md)。

「加入喜好時也加入資料庫」「加入歌單時也加入資料庫」預設皆關閉。啟用後，主操作確認才送額外Library新增；未知結果用GET重查，明確被拒時只重試Library步驟，不重送已成功的喜好／create／append。見[偏好與部分恢復](../work/parity-completion/20261103/library-addition-preferences.md)。

本地UI短測已涵蓋2,600首完整排序／Z定位／跨頁篩選／清除、取消收集、資料夾載入中Back及兩層資料夾返回。這些fixture檢查真renderer與遙控路徑，並非真帳號雲端寫入；root表單權限／新增後讀回的最新結果以[本輪Library紀錄](../work/parity-completion/20261103/library.md)及主工作紀錄為準。

## 大歌單播放與Shuffle

Library playlist tracks每頁上限100，catalog playlist tracks上限300；不變更server回傳的offset／cursor／明確limit。`LibraryPage.total`可選且可能包含MV；raw count／song raw offsets另行保留，不能用過濾後歌曲數代替抽樣人口。

一般Play立即移交已載頁面與continuation；有可靠raw total時，Shuffle可均勻取 `offset=N&limit=1` 的真occurrence先播，再由同一runtime背景讀完完整source，驗證後一次發布tail。重複歌曲按位置保留，無可靠抽樣證據才完整載入fallback。checkpoint、關Shuffle、manual Next／Last及服務無snapshot-token的限制見[大型歌單實作](../work/parity-completion/20261103/large-playlist.md)，不能繼續寫成所有Shuffle一律先全載。
