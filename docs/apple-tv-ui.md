# Apple TV 17 介面更新

目前介面規則與來源界線。遙控方向與返回層級見 [操作規則](tv-navigation.md)；各候選的歷史驗證已保存在忽略版控的 `work/repo-review/20261104/archive/docs/`，不把舊候選結果當作目前全 App 驗收。

本輪範圍包含主畫面、搜尋、設定、專輯／歌單頁與底部播放控制。字型選擇也涵蓋歌詞。

## 已實作

| 區域 | 目前行為 |
| --- | --- |
| 導覽 | 左上目前頁面膠囊；Right 收起並恢復目前 deck，確認目前 tab 回根頁垂直頂部，其他 tab 保留堆疊／捲位；展開採 damping 25／stiffness 185 的彈簧換算 |
| 根分頁轉場 | 150ms 線性淡出後才提交頁面、再 150ms 淡入；無上下位移；低效能立即切換，播放器轉場獨立 |
| 新發現資料 | 匿名讀取 Apple Music 公開 `/new` 的真實 server models；標題、排序及資源 ID 沿用來源，標準 shelves 無假新歌榜單或大卡 |
| 瀏覽座標 | 依顯示面積換算 1920×1080 設計座標，減少 Android TV DPI 差異；不改播放器舞台密度 |
| 首頁卡片 | 依使用者照片改為200×278dp直式推薦卡、124dp方形專輯封面、左下疊字、柔和封面投影及 0.5dp 主題細線；音樂封面保持方形 |
| Library | 獨立分類列、五欄方形專輯／歌單、圓形藝人、緊湊歌曲列；依官方 next 分頁，無 featured 大卡；藝人可進入資料庫專輯 |
| 搜尋與設定 | 搜尋為透明原生輸入、水平種類膠囊及真實建議；保留 Android IME／游標／語音。設定沿用柔霧與一致控制項；功能及方向鍵流程保留 |
| 專輯／歌單 | 固定左側封面；右側標題、說明、播放／shuffle 與歌曲共用一個 LazyColumn，邊緣漸隱；左上全域導航持續可用並保留離開前的歌曲焦點 |
| 播放控制 | 33dp 獨立 GlassPlayer 按鈕、焦點增加 4dp、75／150ms 焦點轉場、40dp 安全邊界與 300ms 線性淡入淡出 |
| 進度列 | 固定 7dp 膠囊、遙控器模式細直游標、A/B/C 狀態透明度、隨播放位置移動的時間標籤 |
| 字型 | 標準版使用電視系統字型。內建字型版在兩種介面、選單與歌詞使用 Noto Sans CJK TC 完整字集的 Regular／Medium／Bold 字重 |

參考包的電影播放器和 AMATV 的音樂播放器具有不同操作：本輪保留上一首／下一首、歌詞、佇列與接續播放功能，再套用官方控制視覺。這不是官方 remote 電影頁面結構完全等同的宣稱。

## 導航與來源範圍

原包 Sidebar／TabbedPage 的 released、Right、resetTab 與 TabTransition 支持目前的根分頁導航規則；使用者 Apple Music tvOS 實拍則支持音樂版的外觀。兩者不是同一 App 的完整 runtime 證據。原包精確字元 offset、頁面背景與 deck 底色的歸屬，以及 150ms 兩段線性參數，集中列在 [導航來源核對](tv-navigation.md#原包證據與-music-適配界線)。

Host 每 tab 保留 browser／detail stack，僅組合當前頁；Home／新發現的列與欄捲位、Search 的 query／種類結果、Library 分類，以及各 detail visit 的 saveable viewport／焦點來源分別保留。新組合以真實項目重新綁定 requester；離頁停止讀取，不靠全頁常駐組合維持狀態。確認目前 tab 的回頂部，等根內容真正取得焦點才解除 sidebar 自動展開保護；Right 與切頁會取消舊 reset 意圖。

公開新發現使用 `MusicServices.browseShelves()` → `PublicMusicBrowseClient` → `PublicMusicBrowsePage`，不再以歌曲／專輯／歌單 charts 拼成整個新發現頁。匿名資料與版型取捨、真實 fixture 和大小限制見 [公開新發現資料契約](apple-music-capabilities.md#公開新發現資料契約)。它是公共網站的實際 server model 適配，不是 Apple Music tvOS 版型已完全重建；無標題 4:1 hero及未知詳頁轉場仍不冒充原版；RoomPageIntent 已保留真身分、分頁與專用全集瀏覽，不以假 catalog rooms 端點代替。

## 效能界線

- 沿用 lazy shelves、穩定 item keys、畫面位置導向的焦點 cursor，以及每幀一張卡片內容的暖機額度。
- 焦點縮放在 `graphicsLayer` 更新；背景色在繪製階段讀取動畫值。水平移焦不把整個 feed 當作逐幀動畫重組。
- 背景等焦點停留 300ms，使用既有 48px 圖片取色快取，呈現照片中的灰藍／淡紫柔霧。Android 12 以上只對展開選單區域重用錄製指令與區域 blur texture；封面另以96×96三輪模糊產生柔霧底，8張快取共約288KiB，準備好才淡換；沒有全畫面圖片解碼、影片背景或全畫面模糊。低效能模式與舊系統使用半透明底。
- 動態封面使用共用焦點停留門檻，依[動態封面契約](apple-music-web.md#已修的動態封面問題)啟動；隱藏、進入詳情、展開選單、低效能模式及 lifecycle 停止時停用。
- 保留原播放器服務、音樂佇列、播放時鐘和歌詞狀態機。
- 背景單獨保留為全解析度 GPU 圖層，靜止時沿用既有繪製結果；FHD 約增加 7.82MB 顯存。
- 以實際 Compose 1.11.4 原始碼核對陰影快取，發現透明度亦屬遮罩 renderer 的 cache key。改成固定陰影參數，僅於 painter 繪製時合成透明度，並保留 trim／detach 後的重取流程；幾何與畫質不變。依據與後續實測分開記錄在 [陰影快取來源](../work/apple-tv-ui/render-audit/shadow-cache-source.md)。

全解析度封面與原有動畫保持啟用；沒有加入未能解決等待問題的額外卡片快取。正式來源不保留一次性渲染探針，模擬器的 timer／present 結果及實體電視驗證界線見下節。

## 視覺保留與驗證界線

瀏覽與控制介面保留既有照片比例、主題 hairline 及柔影；只有封面產生陰影，文字容器零 elevation。封面陰影不做局部矩形裁切，橫列上下保留20dp，捲動邊界使用固定的縮放／陰影外擴範圍。

歌詞已有獨立的時鐘／繪製優化，不能再以早期 `PlayerScreen.kt` 片段 hash 宣稱與舊 HEAD 全段相同。原始包的hash、字型及設計定位仍保存在[官方證據](apple-tv-ui-evidence.md)。安裝及保留帳號流程見[正式版安裝](standalone-distribution.md)。

效能結果必須區分原始碼、模擬器與實體電視。現有模擬器的process-only timer修正使單次有效首頁窗口actual-present p95約18.10ms、最長33.03ms，未重現100ms級等待；這不是穩定60fps或實體TV通過。完整窗口、來源與變因見[host timer紀錄](../work/parity-completion/20261103/emulator-host-timer.md)。

## 尚未證明的部分

官方原包在現有模擬器上能啟動，但之後只呈現不完整場景，無法完成逐畫面像素比對。官方伺服器下發版型、設備效能分級、材質及最終中文字型仍有差異。使用者後續提供三張 Apple Music on Apple TV 實拍，主畫面與歌單頁改以這些照片為視覺基準；電影 App APK 的寬卡／深色面板已由直式音樂推薦卡／半透明選單取代。照片有透視與拍攝曝光差異，尺寸是正規化後的估算，沒有當成校正過的螢幕截圖。

Sing／Music Videos／Radio 依實際 presentation capability 顯示；目前不可用就隱藏，不因資料端有 metadata 就加入空的導航。大塊空白狀態面板與硬邊假陰影已移除，卡片黑色遮罩及選取邊線強度亦已降低。

此版應稱為依官方證據重做的可執行介面；不能以靜態參數或建置通過宣稱全部效果已達像素級 1:1。下一個比對門檻是可正常顯示官方內容的 Android TV，與 AMATV 在同解析度下的主畫面及控制列錄影。

所有原包 hash、參數、程式定位和觀察限制見 [官方證據](apple-tv-ui-evidence.md)。原始 APKM 與解包內容保留於忽略版本控制的 `tmp/`。Sing／Music Videos在缺少實際能力時隱藏；Radio已由獨立MusicKit JS station adapter承接。資料／SDK能力與可操作導航分別核對，詳見[電台接線](apple-music-radio.md)。

Library API 端點、資料保留及分頁規則見 [Library API](library-api.md)。

## 歌單捲動與導航調整

右側只保留一個 lazy viewport，metadata、說明、操作列及歌曲都是穩定 key 的項目，不再把歌曲鎖在固定標頭下的小視窗。上下各 20dp 淡出，焦點揭露保留 24dp，避免選中項目停在淡出區。遮罩僅使用右側 viewport 的硬體合成層；捲動狀態在 draw 階段讀取，沒有全螢幕 blur 或逐幀重組整頁。

歌曲使用單一選取底板；85ms 位置插值在 draw 階段結合實際 lazy item offset。位移步距讀取包含分隔線的實測 slot 高度，底板只覆蓋 58dp 歌曲本體。快按方向鍵累積最新目標，舊捲動交接不可覆寫，尚未取得目標焦點時確認／MENU 不誤啟動舊歌曲。這裡記錄來源規則，完整遙控與效能狀態仍以對應工作證據為準。

全域導航在歌單及藝人頁上方繪製；展開時僅錄製目前頁面作為局部材質來源，內容焦點暫停。向右收起選單會恢復原歌曲／詳頁；選擇其他目的地切換到該 tab 保留的 deck，再次確認目前 tab 才關閉該 tab 詳情並回根頁垂直頂部。

Apple 回傳的 collection `description` 或 `editorialNotes` 已保留到 Track，優先使用個人 Library 的描述，再取 catalog metadata，畫面把格式標記轉成文字；不製造缺少的說明，也不為每張卡新增請求。

首頁卡片前景在32／64位元均保留原尺寸Offscreen成品圖層，陰影維持外部獨立layer。32位元Auto分支曾在m7632實機增加RenderThread重播成本，已用同來源A/B確認恢復快取的收益；GPU記憶體代價與未收斂尖峰見[2026-11-04實機紀錄](../work/tv-performance/20261104/README.md)。
