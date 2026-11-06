# 音質與 Dolby Atmos

Classic／Apple 介面使用同一組設定與播放器，不複製佇列、歌詞或導航邏輯。

## 設定

- 預設為高音質 AAC、Atmos 關閉，保持既有 MusicKit 播放路徑。
- 高效率 AAC：從帳號實際播放資產選擇宣告頻寬最低的 AAC；取得失敗時沿用既有播放路徑。
- Atmos：在目前裝置具 JOC 播放能力且歌曲有 JOC 宣告時優先使用；否則使用 AAC。選擇音軌不等於已證明音響端 Atmos 輸出。
- 無損／高解析度無損目前是不可選取的資訊列。ALAC 第二段授權仍遭拒，不能標示為可用。
- 設定會保存，下一首或重新準備播放時生效；不打斷當前歌曲。啟用可選音質時，不讓 SDK 預排的下一首繞過此設定。

## 播放邊界

`MusicServices` 擁有 Native、StationWeb、HLS 三種 renderer。原有 runtime 繼續擁有歌曲 occurrence、佇列、重複、隨機、背景播放與系統控制。

`AppleAudioAssetResolver` 使用帳號 storefront、原始 playParams 與正常 `/v1/play/assets`。同回應 AAC 保留來源關聯；公開網頁 enhanced metadata 提供的 JOC 與獨立播放回應沒有被宣稱為已綁定會話。授權一律使用本 App 憑證與本帳號 Music User Token。

解析有 12 秒總期限，Atmos 可選 metadata 有 5 秒子期限。取消會傳遞，CDN 不帶帳號 headers，signed URL、challenge、license、key 與受保護 PCM 不寫入檔案或日誌。

`AppleHlsPlayer` 沿用研究實播通過的 Widevine／內容識別流程，每段使用自己的 public identifier；renewal 只沿用同 CDM session 的既有關聯。使用 Media3 的 HLS parser，不自行維護另一套完整語法。

HLS 失敗時，同一 service generation 只回退 Native 一次，保留 occurrence、最近進度、尚未完成的 seek 與最新播放／暫停意圖。回退期間的舊 HLS 回呼不觸發另一輪 runtime 重試。

AAC 保留 PCM FFT；Atmos 不掛 Visualizer effect，避免破壞 encoded direct/offload。不可取得真實 FFT 時不製造假頻譜。

## 背景播放重入

確認退出後保持背景播放，再開啟 App 進入「播放中」時，不自動重開音訊分析授權。詢問狀態持久保存，避免 Activity 重建後再次觸發系統權限視窗，造成整個視窗暫停／恢復。

Classic／Apple 共用設定中的「音樂律動」授權入口；永久拒絕後可開啟系統設定，返回 App 時重新讀取授權。此修正移除一條確定會切換整個視窗的路徑；尚未在原回報裝置確認是否涵蓋其整頁閃爍。

## 離開時的播放選擇

播放或等待起播時，離開視窗提供繼續使用、離開並繼續播放、離開並停止播放。三語按鈕共用原有元件，以直向排列維持足夠文字寬度；預設焦點在繼續使用。

停止後離開沿用既有 Pause 路徑：先取消 runtime 的準備、重試與待播意圖，更新 renderer 的最新暫停意圖，再關閉睡眠計時並離開 Activity。保留佇列與播放位置，不用清空整個播放工作階段達成停止。單純離開仍保持原有背景播放行為。

## 廣播恢復

電台錯誤回報不受 SDK Stop 的同步清理例外影響，避免已停止回報的播放器把畫面留在緩衝中。使用者仍要求播放、播放器卻退到 None／Stopped 時，沿用既有 30 秒進度期限與有次數上限的重連。一般 Pause、音訊焦點暫停及正常結束不觸發此恢復。

先前 Play 較晚完成時，會保留最新的 Pause 意圖；補償失敗只屬於該次命令，不終止後來的新 Play。命令完成後的同步清理例外同樣沿既有錯誤路徑回報。

針對實際 bootstrap 的 Node 回歸及 17 項進度／重連 JVM 測試通過，standalone R8／lint-vital 建置通過。原回報電台尚未提供，不能將這兩條修正等同其裝置的實播確認。

## 歌名的內容標示

Classic／Apple 共用 `TrackTitle`：只有已標示 explicit 的歌曲在歌名旁顯示 E。播放中、佇列、搜尋與主要歌曲卡片／列表沿用同一元件，非歌曲容器不新增標記。原生 metadata 及播放中資訊更新保留 contentRating，避免開始播放後標記消失。

## 連線失敗

Native queue 錯誤只有在 cause 明確是 TLS、DNS、連線或逾時例外時，才沿網路失敗處理；不把一般佇列／DRM 例外全部改成網路重試。握手失敗提供三語日期、時間與網路檢查提示，連線驗證保持原有安全要求。

2026-11-06 模擬器實際慢了 28,204 秒，當時 API 握手失敗且 SDK 包成 type 3／MIN_VALUE。僅校正裝置時間後，同 APK、帳號與 explicit 曲目 `1537477069` 的 API 回 200，正式 Native 播放觀察到 131,561ms，播放中 E 也確認可見。這能解釋此次本機失敗，不能外推為其他回報者同因；也不是 ALAC 完整播放證據。

## 驗證範圍

2026-11-05 的隔離原型已在實體電視自然播完曲目 `1776914757`（約 301 秒），確認 active E-AC-3 JOC、HDMI ARC、direct/offload、零 underrun。此證據不等於本次正式整合的完整佇列／切歌／背景與輸出驗收。

本次整合執行音質選軌、授權狀態／來源、回退進度與既有 runtime／native rail 的針對性 JVM 測試，以及 standalone R8 建置。仍需於可用的 Atmos 電視／音響上確認整合版的實際輸出與跨曲操作。無損授權研究留在獨立 research checkout。

0.6.3 候選版集中驗證 79 項既有針對性 JVM 測試（含載入中暫停、舊準備結果不能回寫、回退時保留暫停／seek、選軌與網路分類），全數通過；Station Node 回歸、standalone R8 與 lint-vital 亦通過。使用者更新說明另見 [0.6.3 更新內容](release-notes-0.6.3.md)。

模擬器上的候選版已短測離開流程：遙控器可從預設繼續使用移到兩種離開選項；繼續播放後 Activity 關閉且重入仍為 Playing，停止後 Activity 關閉且重入維持 Paused。暫停狀態只提供一般離開，返回可關閉視窗。視窗截圖已確認文字與焦點框未被裁切。未因此重跑全頁巡迴，也未宣稱涵蓋原回報裝置的閃屏或整合版 Atmos 硬體驗收。
