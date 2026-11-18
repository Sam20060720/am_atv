# 手機掃碼登入

1. 讓手機與電視連上同一個 Wi-Fi。在帳號頁選「在手機上登入」。
2. 用手機掃描電視上的 QR Code，依手機畫面開啟 Apple 登入頁並完成授權。
3. 電視顯示「已完成登入」後即可關閉畫面。代碼過期可按「重試」取得新代碼。也可以回帳號頁選「在這台電視登入」。

手機打不開掃碼頁面時，確認手機可連到電視。部分訪客 Wi-Fi 會阻止裝置彼此連線。

## 程式如何接收登入結果

`PhoneMusicAuthorization` 在登入期間開啟區網 HTTP 連線，使用隨機且只能成功提交一次的網址。QR Code 的有效時間為 15 分鐘，離開登入畫面會關閉連線。手機上的 Apple 登入頁使用 HTTPS。Apple 的 MusicKit JS 完成授權後，手機送出 Music User Token，電視驗證帳號後儲存。

伺服器會檢查請求的 `Host`、`Origin` 及 QR Code 的隨機識別碼。以模擬器測試時，透過 `adb forward` 轉發配對連接埠。啟動 App 時，用 `dev.amatv.phoneAuthAuthority` 指定手機可連到的主機位址及轉發埠，用 `dev.amatv.phoneAuthPort` 指定模擬器內的連接埠。測完移除轉發。
