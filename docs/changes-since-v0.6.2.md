# 與 v0.6.2 最後提交版本的差異 — 2026-11-04

比對基準為 `6754603`（`release: prepare v0.6.2`）與後續本地實作，涵蓋初次整理時未追蹤的新來源；不是 `HEAD~1..HEAD`。這是功能、資料來源與播放生命週期的大修改，並非只有首頁效能微調。這批變更隨本地測試版整理提交。

使用者已認可既有介面與手機 QR 登入。本輪沿用版面、卡片、字體、陰影與動態效果，補操作入口、非同步狀態與可恢復行為。新增設定的預設值維持既有外觀及帳號行為。

## 具體更新

| 功能群 | 相對 6754603 的變化與實際用途 |
| --- | --- |
| 登入與帳號 | 手機 QR 短期配對、回傳驗證及資源關閉；保留 TV browser 登入。已登入隱藏 QR 入口，未重做已確認正常的登入。 |
| 首頁完整內容 | 既有棚標頭可進完整歷史、推薦群組及 Replay；沿用 Insights browser、來源 ID、分頁與返回堆疊。個人棚保留真 next／total／子群組資訊，只有首頁外還有內容才提供「顯示全部」，完整小棚不再開重複頁；不裁切首頁。 |
| 全集瀏覽 UX | 純歌曲改直向列，封面內容改多列網格；直接延續首頁棚標題，移除無關來源側欄。推薦保留群組、Replay保留年份／分類；近尾自動分頁、子頁返回原項目。Room多區段保留各自cursor／viewport，沿用原browser與單一共享body。 |
| 首頁按鈕與封面閃爍 | 標題動作直接使用既有ArtistTextAction與固定key；動態封面crossfade被中斷時保留兩層當下透明度，避免突然提升為不透明。保留原動畫與單decoder；沒有新按鈕框架或快取。 |
| 新發現／公開專題 | 公開編輯棚與五類榜單／曲風整合在外頁；標頭保存真 Room see-all，接初頁及後續 cursor，能超過原首頁預覽。節目策展者補原網頁單集棚及完整 Room；探索更多的四個真Room入口沿既有文字卡接回。 |
| 搜尋 | Catalog／Library 分類、各類分頁、建議、歷史，以及離開鍵盤後的遙控器交接；保持真 Library 身分。 |
| 共用輸入框 | 搜尋、分類篩選、建立名稱／描述、管理改名5處統一為TvTextField；共用native焦點／hover、placeholder／label、游標與選取色，保留兩種尺寸與各頁IME／業務流程。搜尋維持透明大字樣式，Compact 表單才使用白底焦點；共用輸入機制不統一不同用途的外觀。篩選修正固定背景導致看不出focus／hover的問題。 |
| Library | 最近加入、專輯、藝人、歌曲與根目錄 children；真資料夾 children／parent；根播放列表工具列可建立第一個資料夾／歌單。完整資料排序（含歌曲／專輯的藝人順序）、篩選、字母定位與跨 tab 恢復，不保存另一份原始資料。 |
| 焦點與返回 | 收斂 Artist 跨區交接、Insights 舊 visit、Resource 空區段、IME／Library 工作及載入途中 Back；過期回呼不能搶焦點或導航。保留同一 Host／focus entry。 |
| 資訊與快捷 | 一般項目及 Queue 均可開資訊；「顯示專輯」沿真 albums 關聯。完整 credits 解析演出、詞曲、製作與工程角色，使用原資訊頁，不拿 composerName 當完整製作名單。 |
| 分享 | More提供分享／複製真 canonical URL；TV直接沿用既有 QR，其他裝置有可用目標時開系統分享。沒有新網路請求、網址猜測或分享框架。 |
| 帳號寫入 | 喜好、資料庫、rating、建立／追加歌單有結果確認。結果不明時 Refresh 讀回，不自動重送 create／append。兩項預設關閉的偏好可在加入喜好／歌單後也加入資料庫；附加步驟失敗不改寫主要成功。 |
| 原生雲端管理 | 依真 web 協定及正確 Origin 接管理 service與同一 action sheet子pane。改名、完整 occurrence重排已用專用資源寫入並讀回；刪除依 fresh metadata／membership確認 soft deletion。能力逐項設 gate。 |
| 大歌單 Shuffle | 有可靠 raw total時，均勻取 `offset=N&limit=1` 的 occurrence先播；同一 runtime背景補齊完整人口，再一次發布隨機尾段。保留重複歌曲、關閉Shuffle、手動Next／Last與重建恢復；無可靠total／索引證據才回完整載入路徑。 |
| 佇列與歌曲電台 | app／原生 occurrence對應、手動編排與continuation同屬既有queue owner；明確「建立電台」以選定歌曲原子切換到Apple Music seed station，不先替舊seed發請求。 |
| 播放生命週期 | `PlaybackRuntimeOwner`讓UI重建／背景播放與Compose scope分離；Activity／Service以lease接回同一queue／player，閒置時釋放。 |
| 睡眠計時 | More提供Off、15／30／45／60分鐘、本曲結束與本專輯／歌單結束。沿用runtime單一job，按occurrence／原container判定，不讓手動追加／無限推薦延長原專輯尾端。 |
| 電台與單集 | 官方MusicKit JS station引擎、真曲名／封面、FFT、直播Stop／live-edge重連；有限單集使用原游標，item／duration晚到仍能一次恢復。Radio標頭可進真station-genres。 |
| 系統EQ | 只有prepared原生歌曲、有效audio session且系統有面板才提供入口；Station不沿用舊歌曲session。使用系統面板，不另建音訊引擎。 |
| 歌詞可讀性 | 原Lyrics dialog新增歌詞／發音何者較大，預設保持歌詞優先。實際文字與行高估算共用尺寸政策，切換重算量測／錨點，不為字級重抓歌詞。 |
| 圖片與素材 | 共用Coil LRU、viewport預載、48項metadata lookup與影片segment cache；lazy重掛恢復已補齊素材／配對poster。單一裝飾decoder、Surface ACK、回呼身分與低記憶體處理，避免回捲骨架／不合法釋放。 |
| 繪製與工具 | 隱藏內容時鐘閘、歌詞區間／字元快取、陰影mask、CPU背景等值優化及Foundation idle-prefetch期限backport；另有variant隔離的短測、追蹤、故障注入及保留資料安裝工具。 |

## 原生管理與 Shuffle 的確認界線

本輪已用唯一命名的臨時歌單驗證建立、追加、改名及重排，完整讀回保留 `A,B,A,B` 的四個occurrences；offset 0／1／3也保留原始位置。刪除不是只看HTTP或要求GET404：服務會保留tombstone，需確認metadata轉為刪除狀態，並由原parent／root的完整membership消失。後續recovery已確認臨時歌單與空資料夾清理完成。

目前已啟用歌單改名／重排／刪除、空資料夾刪除、資料夾改名／移動、取消喜好及有明確catalog映射的歌曲移出Library。`management-remaining-01`已確認資料夾改名／移動；`favorite-library-02.json`四項寫入通過並完成cleanup。service實證、production gate與實際UI驗收仍分別記錄，不能將一層通過擴大成全部原生App功能完成。先前省略Origin的401已找到原因，不能再推論原生管理一律不可用。詳見[管理追查](../work/parity-completion/20261103/cloud-management.md)及主工作紀錄。

Shuffle fast path以可驗證raw total／offset occurrence為條件，沒有用首100首近似全歌單。sample不可信時保留同一次raw draw供fallback，避免只重抽容易驗證的位置造成偏差；完整tail驗證後才發布。服務沒有snapshot token，同總數的並行編輯仍可能無法完全偵測，這是外部限制。詳見[Shuffle實作與測試](../work/parity-completion/20261103/large-playlist.md)。

## 程式合理性與維護成本

- **沿用owner與頁面。** Insights／Rooms／credits／Queue資訊走既有browser／resource頁，計時與Shuffle走既有runtime／queue；分享重用QR，EQ開系統面板，管理是action sheet子pane。沒有為每項功能加播放器、常駐服務或導航框架。
- **快取分工有用途。** 圖片、影片segments、feed快照、小型metadata與掛載身分的資料／生命週期不同；沒有第二套bitmap map或通用cache框架。素材恢復直接peek現有LRU。
- **來源欄位解決具體問題。** raw offsets保留Shuffle公平性，`containerEntryIds`辨別原專輯與手動項目，entry IDs保留同曲重複位置；支援真恢復與尾端停止，不是未使用功能的預留。
- **web transport共用。** 管理、Rooms與credits重用HTTP transport／web-token owner；GET才可依契約刷新一次，寫入不自動重送。可選資料拒絕不拖垮一般資訊。
- **移除確定未接線路徑。** 獨立Charts輔助頁的browser／screen及Host焦點／callback接線不可由UI觸發，已移除並保留恢復副本；New既有五類inline榜單完整保留。
- **舊預備碼不當最終成果。** 未用的歌曲種子wrapper／未來網頁fallback已移出production。早期管理預備碼的移除是歷史步驟；目前管理依新服務證據接回，不能再把「737行移除」當最終淨減量。
- **維護熱點仍在。** Foundation精確版本／opcode backport應隨上游修正升級一起移除；UI與背景的prepare／重試已收斂至runtime單一owner，UI只讀取準備狀態；snapshot展示與native rail仍各有實際生命週期。這輪沒有為減行數重寫播放器或刪除Surface／generation防護。

## 元件與工作樹清理

另完成一次實際引用審查：446檔減為422檔，活躍程式淨減1,788行（正式程式−186、benchmark−1,491、工具−113、測試＋2）。共用QR矩陣繪圖／同款對話框按鈕，三處動畫observer改用既有helper；移除141行退役API，7份一次性調查及接線移出active source。18個小型三語資源檔按domain合併，移除27條無引用翻譯，其他1,699項resource語意不變；station engine併入radio並修正過期功能描述。

保留有實際用途的runtime owner、資料身分、各類快取、回歸測試及prefetch patch。所有移出來源、原始文件與整棵dirty tree已有恢復副本，沒有用ignore規則隱藏仍使用的程式。詳見[清理理由、前後統計與恢復資料](../work/repo-review/20261104/README.md)。

## 驗證與尚未完成

B6完整單元測試 **862項通過**，最終mutation針對性測試另有 **41項通過**。另有真服務讀回、模擬器短路徑、公開Rooms100→200→返回及lazy重掛通過、Library大量資料／資料夾、素材生命週期及其他直播／有限單集的個別證據；成功、失敗及修正重測以[本輪主工作紀錄](../work/parity-completion/20261103/README.md)為準。單元測試不能替代系統EQ、睡眠尾端、全部管理、真斷線或實體TV的60fps。

尚未宣稱原生App全功能或全頁所有快速操作驗收完成。本輪已完成上述雲端寫入與cleanup的專用驗證；各裝置的大型Shuffle、內容尾端計時及管理UI仍以實際已覆蓋路徑為準。Lossless／Atmos真輸出、音訊Crossfade、Sound Check、受保護離線下載及Casting不因metadata或原App資源存在就視為支援；MV／Sing依實際能力隱藏。全面穩定60fps仍需有效的實體裝置present／trace證據。

這輪確實有授權範圍內的臨時雲端資源驗證與清理，不能沿用前階段「沒有操作雲端歌單」的敘述。使用者既有歌單不是測試標的，手機QR登入也沒有重做。

## 64 分區後續審查

依使用者要求完成64個分區的subagent程式審查，重複發現合併處理。正式程式再淨減255行，沒有新增正式來源檔／owner；刪除45條未引用翻譯與1份舊layout。修正重點是：UI／背景prepare與重連改由原runtime統一管理；跨Repeat.All／Previous的canonical source occurrence與待續頁Play Last順序；checkpoint／full context混代；Library刷新權威替換與索引暫停；action Refresh晚到結果；palette／快取設定race；token取消、真TTL、空Room續頁與未使用推薦嵌入樹。

64分區審查完成時，相對`6754603`為434個變更項目；比先前422多的12項是9個原有檔納入修正及3個新增回歸測試，不能當成新增12個正式元件。表面檔案數沒有用來取代程式合理性判斷；保留必要快取、Surface ACK、不同版型與版本相依patch。完整處置、每區結果與逐檔差異見[64分區審查](../work/repo-review/20261104/sweep-64/README.md)。

## 本地測試版交付 — 2026-11-04

- 分享用 APK：[AMATV-0.6.2-region-fix-20261104.apk](../dist/AMATV-0.6.2-region-fix-20261104.apk)，約22.6 MiB；Android 9以上，包含ARM32／ARM64／x86／x86_64。
- 選用的ADB安裝套件：[APK＋對應baseline profiles](../dist/AMATV-0.6.2-region-fix-20261104-install-kit.zip)。單獨APK也可正常側載。
- 版本維持0.6.2／versionCode15。使用既有`standalone` variant，沿用本機既有開發簽章以保留更新相容性；繼承release的R8與資源縮減，除錯及cleartext關閉，沒有測試fixture／probe元件。
- SHA-256：`d772cb6a30940870cedd5480a85fb7cb3b73da6e8f7ca3f07e0ffdbb6f4da213`。本次為帳號地區全面修正版，包含前次播放啟動修正，已保留資料安裝至模擬器。
- 真正的播放錯誤維持清楚提示；一般準備／緩衝超過1.5秒才以淡色小字顯示。包含首頁卡片快取、搜尋樣式、冷載封面、即時準備動態素材，以及歌詞跳轉後回進度條的修正。

播放修正版完成82項針對性單測及模擬器歌曲／切歌／電台啟動短測；本次地區修正版另完成54項單測及兩份JS契約檢查、正式建置與APK＋DM配對。先前909項單測屬64分區檢查，後續封面17項與緩衝提示8項屬各次針對性驗證，不能當成本次新跑的結果。實體TV首頁改善已由使用者確認，上下仍有偶發尖峰，未宣稱全頁穩定60fps。

實體TV保留先前已確認順暢的版本；[對應復原安裝包](../dist/AMATV-tv-card-cache-install-kit.zip)仍在。本次不操作電視。只做本地Git提交，沒有push、tag或公開發佈。

## 本次產物清理

已移除歷次測試的模擬器備份、舊APK／重複解壓安裝包、舊原始追蹤與畫面擷取、一次性解包／工具鏈及專案編譯快取。保留目前運作中的模擬器、必要來源復原檔、最新實體TV量測、修正摘要、正式宣傳影片及仍有用途的回歸測試。先前紀錄中的舊產物路徑為歷史位置，部分已清除；本節連結為目前交付位置。

逐項清理清單保存在[清理紀錄](../work/repo-cleanup/20261104/README.md)；目前APK的建置、安裝與R8 mapping見[地區修正交付](../work/storefront-audit/20261104/README.md)。來源與版本比較仍以本文件前述功能群為準，不能把被忽略的測試產物占用量當成Git程式差異。

## 播放啟動回歸修正

播放必要的歌曲／Library映射、storefront及credential讀取使用保留的2槽HTTP排程，沿用既有connection pool，避免被一般4槽首頁／素材請求阻塞。當前歌曲確實產生進度後才預載下一首；手動下一首若app occurrence、catalog ID、原生queue ID、憑證及generation均吻合，確認handoff後沿用該原生項目，避免清掉已備好的provider再prepare。確認不成立則完整準備同一目標。電台使用真帳號地區，取消1.5秒後誤用預設地區的fallback。

82項針對性測試及正式建置通過。模擬器首次prepare、手動原生項目重用與Apple Music 1啟動均有短測；電台冷載此次仍約5.3秒，主要在官方腳本及串流準備。完整原因、界線及交付見[播放修正紀錄](../work/playback-regression/20261104/README.md)。

## 帳號地區全面檢查

移除已登入Catalog查詢失敗後回到build-time地區的共用fallback，並固定storefront請求的帳號token、拒絕換帳號後晚到的舊結果。電台不只metadata，現在連MusicKit configure都明確指定帳號storefront；歌詞與電台的地區參數改為必填。YouTube Music的固定TW改由目前播放目錄地區提供，公開內容／motion共用同一provider。舊feed快取改用新namespace，避免顯示舊版錯誤地區資料。

BuildConfig預設地區已明確改名為`APPLE_MUSIC_GUEST_STOREFRONT`。保留未登入的可設定預設、匿名web bearer的US bootstrap、官方數字地區對照與語言選項；這些用途不決定已登入帳號的目錄地區。54項單測與兩份JS契約測試通過，未宣稱跨國真帳號播放驗收。完整盤點見[地區檢查紀錄](../work/storefront-audit/20261104/README.md)。
