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
