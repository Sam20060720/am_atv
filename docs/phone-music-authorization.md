# 手機掃碼登入

2026-11-01：新增 `PhoneMusicAuthorization`。電視在登入期間綁定區網介面的臨時 HTTP port，QR 使用 192-bit 隨機 session 路徑；手機從 Apple 官方 CDN 載入 MusicKit JS，在使用者按下「繼續登入」時呼叫官方 `authorize()`。Apple ID／密碼僅在 Apple HTTPS 頁面輸入，不由電視代理。

授權 popup 完成後回到手機原頁，用相同 origin 的一次性 POST 將 Music User Token 交給電視，再顯示連接完成。若 Apple 流程改用 redirect hash，僅由官方 MusicKit library 消化；程式不把 hash／token 放進 URL、紀錄或第三方分析。既有手機瀏覽器的授權不會在掃碼後未按按鈕就自動綁定電視。

伺服器限制 Host、Origin、session nonce、HTTP header/body 長度、兩個同時連線與四個等待連線。session 15 分鐘失效，成功只能使用一次；離開登入即 close，不保留常駐網頁伺服器。回傳的 token 格式檢查不等於 Apple 驗證，主流程仍須用既有服務驗證後儲存。

模擬器的 10.0.2.15 不能直接讓手機連線。測試由主機 `adb forward` 建立區網可達 port，並用 `advertisedAuthority` 指定主機 LAN address:port；實體電視則直接使用其 LAN address。僅本機／預設路由不等於手機已可達；要另外確認網段與防火牆。

## 證據及驗證界線

- 官方 [configure](https://js-cdn.music.apple.com/musickit/v3/docs/iframe.html?id=reference-javascript-musickit--page) 與 [authorize](https://js-cdn.music.apple.com/musickit/v3/docs/iframe.html?id=user-authorization--page) 經 Context7 核對。
- 本地已擷取的官方 `work/apple-music-web/source/musickit-station.js`：`authorize` 走 `requestUserToken` → Apple `ServiceSetupView` popup；登入頁 HTTPS，透過 opener/dispatch 回報授權。未見該路徑直接檢查 `isSecureContext`，不等於手機 Safari/Chrome 一定接受 HTTP LAN origin。
- Apple 的 Music User Token 有應用程式／裝置作用域限制。手機產生的 token 是否可被電視接受，仍必須以帳號 API、完整歌曲播放、重新啟動後保留逐一證明；這不代表 Apple 正式保證的跨裝置登入。
- 本地server反例只驗證一次性傳輸、origin／nonce／host、長度限制與close；已完成的手機授權、重啟及原生播放證據見下節，不能用HTML可載入替代登入完成。

## 模擬器連線與既有證據

模擬器的區網轉發測試使用本機腳本 `tools/phone_auth_forward.py`。`tools/` 是本機診斷目錄，Git 會忽略它。`advertisedAuthority` 必須是手機可連到的主機區網位址與臨時連接埠。原實驗的 `phoneAuthority`／`phonePort` 設定及配對結果已保留在忽略版控的 `work/phone-login/20261101/`。一次性啟動測試已歸檔，不再作為日常登入的程式入口。

轉發只應維持本次配對期間，完成後關閉relay並移除對應ADB forward；工具不解析或記錄HTTP流量。手機端僅回報ready／authorize及有限錯誤階段，不回傳Apple登入欄位、帳號或token到診斷紀錄。

## 2026-11-01 實際結果

既有手機與模擬器／帳號組合已完成Apple授權、候選token的個人storefront驗證、儲存及重啟後原生歌曲播放，播放超過30秒預覽界線。結果見`work/phone-login/20261101/experiment.json`及`native-after-restart-verified.json`；一次性server、LAN relay及ADB forward已關閉。當次候選識別、游標還原與失敗重試過程留在該工作證據，不作為產品協定。

這只證明已記錄的手機／模擬器／帳號組合可用，不擴大成Apple正式保證的跨裝置授權。手機QR登入已由使用者確認；已登入時隱藏登入入口，不要求重做登入。

一般播放／選單要求登入時預設使用手機流程；帳號頁仍明確保留電視端登入作為另一個選擇。
