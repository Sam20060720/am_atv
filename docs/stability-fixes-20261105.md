# 2026-11-05 穩定性修復整理

本次將已完成驗證的變更依依賴順序分批提交。Classic 與 Apple 介面的既有設計保留，重點是操作、資料生命週期與播放一致性。

| 批次 | 具體變更 |
| --- | --- |
| 授權 | 共用憑證持久化鎖；取消或換帳號後拒絕舊授權結果；修正 Web callback 解析；關閉手機配對連線；簡繁登入文案。 |
| API／地區 | 請求鏈與快取綁定帳號；依 storefront 支援語言送出請求；公開 HTML 使用可取消、有總期限的共用 transport；電台補曲使用播放保留工作資源。 |
| 播放 | 過期 native handoff 拒絕與 reconciliation；保存 occurrence 對應進度；分頁 Next 失敗可重試；Pause／Seek／Reconnect 期限；避免 checkpoint 重複；電台新歌曲事件更新曲名與封面。 |
| 歌詞 | TTML 語言與字系篩選；翻譯／發音獨立保留；已解析素材直接投影；設定不被素材載入狀態卡住；靜態歌詞可手動捲動且不誤 seek；切換 occurrence 重設暫態輸入。 |
| 瀏覽／焦點／封面 | 帳號與語言失效整合；Radio 依成功來源替換；Home／Search 共用最近播放；Library 寫入後失效與讀回回饋；選單返回來源列；移除焦點目標時交接；封面掛載保留、色盤 fallback、分介面預載尺寸；隱藏畫面停止無用動畫。 |
| 測試工具 | Classic／Apple 導覽 helper、focus／selected 語意、continuity 每次執行識別，以及符合目前 Show All／Replay 行為的 fixture。 |

## 已有驗證

- 分批提交前的最終主機檢查：982 個單元測試通過，0 failures／errors／skipped。
- Debug APK 建置、App 與 benchmark 模組的 benchmark Kotlin、standaloneGecko Kotlin 編譯通過。
- Production station now-playing 與 spectrum JavaScript 回歸通過。
- 模擬器確認電台歌曲封面及首頁動態封面正常。冷素材 HTTPS 失敗另查明為模擬器時鐘落後，校時後恢復，未加入 TLS 信任例外。
- 提交整理使用逐檔 SHA-256 比對，確保既有 112 個變更檔的內容保留；未因整理提交重跑裝置巡迴。各中間 commit 未逐一建置，以上驗證對應整合後的最終程式。

## 尚未驗收

- 中國區帳號 Radio：使用者尚未登入該帳號，未取得真帳號失敗回應。後續需區分目錄、帳號授權、SDK queue、串流／DRM 與官方地區供應情況，保留帳號真實 storefront。
- 電台私有背景 host 在各款實體電視上的背景音訊／DRM 行為，以及各裝置實際 FPS，不能由主機測試代替。
- 本次沒有發布 release，也沒有推送遠端。

完整分區審查與短測證據保留於本機 `work/repo-review/20261105/code-audit.md`；該目錄沿用忽略規則，不提交一次性報告、記錄、APK 或帳號資料。

## 後續操作修復

- **失焦：** 純載入／空白提示不再作為焦點目標；首頁分頁尾卡從取得焦點起即保存交接意圖，涵蓋後續背景載入。手機登入與離開確認納入共用 modal 交接；帳號替換清除舊導覽意圖。瀏覽 host 只在前景、沒有 modal 且缺少實際焦點時恢復輸入，保留已聚焦的卡片或導覽控制。沒有可用內容時立即提供導覽焦點，不等兩秒超時。
- **歌單反轉：** 設定預設關閉，兩種介面共用。開啟後 catalog／library 歌單由來源尾端逐頁向前讀，顯示與播放 queue 同序，保留重複歌曲的 occurrence。從中間選播、續頁與 checkpoint 保存方向；切換偏好不重排既有 queue，返回保留的瀏覽歌單則重新載入。專輯、雲端歌單寫入及複製保留原始順序。這是來源順序反轉，手動重排過的歌單不等同依加入時間排序。
- **反轉載入成本：** 有 total 時首次僅讀起始資訊與尾端一頁；缺 total 時需先逐頁計數，但不保留整份 metadata，且整條首次讀取共用 25 秒期限，失敗走既有重試流程。
- **歌詞去重：** 僅在 render projection 比對同一主唱／和聲段落的原文、翻譯及發音，處理逐字、整句、空白與引號差異；不刪除不同時間的重唱，也不改原始素材快取或歌詞時序。排版估高與繪製使用同一份去重結果。

整合後 Debug APK 建置成功；106 個相關單元測試通過（0 failures／errors／skipped），包含反向分頁／選播／queue restore、既有分頁及 shuffle、歌詞去重／高度／素材切換，以及焦點邊界與 modal 輸入。新增反向分頁 fixture 曾因未等待非同步 parser 而失敗，已改為等待 loaded／onReady，未變更順序與 occurrence 的預期。這些檢查不代替實際遙控器驗收。

已保留資料安裝到 `emulator-5554`；未操作實體電視。APK：`app/build/outputs/apk/debug/app-debug.apk`，103,440,563 bytes，SHA-256 `ab386a12b279dacfaed91592485a3e152bd97073a6e8b9fe4bbdd2fdcfebf179`。

中國區後續僅完成程式與匿名官方目錄核對：[官方中國區廣播頁](https://music.apple.com/cn/radio)有提供風格電台，不能把「所有電台失敗」直接判成不支援中國區。帳號尚未登入，實際授權、SDK queue、串流及 DRM 的失敗層仍待真帳號回應。


### 暫停／冷啟動時選擇歌詞段落

歌詞選段使用獨立的絕對時間「跳段並播放」意圖，套用於控制列顯示與隱藏兩種路徑。已準備好的同一 occurrence 先送 Seek 再 Play；未準備好、準備中、重試中或歌曲時長尚未知時，由既有 runtime prepare 帶入目標時間，保持引擎暫停至準備完成。選取新段落會取代較早的 prepare serial；使用者後續 Pause 仍可取消播放意圖。

完整 session 尚在磁碟還原時，沿用既有 startup cursor 編輯與 Play gate，完成 hydration 後才開始。若在等待下一頁歌曲，明確選取目前歌詞會取消本次 advance，保留未載入的 collection tail；後續頁到達只補 queue，不再自動跳走。授權恢復保存所選段落；靜態／未定時歌詞維持不可跳段。控制列可見時，緩衝期間保留可操作的播放按鈕，進度條可用後回到進度條；新的遙控器操作會取消這個待返回目標。

本項 Debug APK 建置成功，84 個相關測試通過（0 failures／errors／skipped），包含新增的延遲解析仍保留絕對時間、同 occurrence 最新段落取代舊準備，以及取消等待但保留後續 queue。已保留資料更新並啟動模擬器；尚未進行這條新增路徑的實際遙控器驗收。
