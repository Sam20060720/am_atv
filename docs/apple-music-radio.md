# Apple Music 電台接線

公開資料核對於2026-11-01；播放協定與FFT整理於本文。資料瀏覽、可接受請求及實際播放分別記錄；有限的成功案例不代表所有電台、單集、地區或裝置均已驗收。

## 官方來源

匿名 GET `https://music.apple.com/tw/radio` 的 `RadioPageIntent` 保存在 `work/apple-music-web/radio-page.html`／`radio-page-data.json`，HTML SHA-256 為 `0c6bf4f66dc700d97a286623446d658406f40757fe99ed74bdfeb369c28aa2fd`。沒有 Cookie、User Token、Developer Token 或帳號寫入。

當日頁面依序提供六個免費直播電台、20 個訂閱單集、20 個訪談影片、18 個廣播電台。免費直播使用 `liveRadioGridLockup`，標題在 `accessibilityLabel`；單集為 `horizontalLockup`，廣播為 `squareLockup`。全部使用實際 `contentDescriptor` 的 `radioStation`／`ra.*` 身分。訪談是 `artistUploadedVideo`，不是一般歌曲、MV 或電台，沒有相應播放路徑時排除。內容與數量會隨地區和時間變動。

既有出貨網頁 `work/apple-music-web/source/index.js`（SHA-256 `752cc36a456f41e8b648791ea1f890a0d8e4adef86c9bfc5409018da7ca94a93`）字元位置 246308 附近區分直播、廣播、單集的 `isLive`／`playParams.format`／`streamingKind`；1687075 附近使用 MusicKit `setQueue({station: ...})` 後 `play()`。它沒有將任意 catalog 電台轉成歌曲佇列。

原生 AAR 1.1.1 不提供 Station container 常數的限制保持有效，詳見 [網頁能力研究](apple-music-web.md)。Web 電台路徑由獨立 `stationWebPlaybackAvailable` 表示；沒有向原生 SDK 填入猜測的常數。

## 資料與介面

`PublicMusicBrowseClient.radioShelves()` 讀取匿名官方頁面，沿用現有 IO／Default dispatcher、4 MiB HTML／2 MiB JSON 上限、同站 HTTPS storefront 重新導向及取消檢查。`PublicMusicBrowsePage.parseRadio()` 明確挑選正確地區的 Radio intent，保留伺服器區段順序，將直播列接到既有 TV shelves，非方形電台圖保留來源比例並以 Fit 顯示。普通連結 grid 不會因為這項支援而被當作電台卡片。

`RadioCatalogApi.page()` 提供以下三個獨立來源，共用既有 authenticated transport：

| 來源 | 端點與授權 |
| --- | --- |
| Live | `/v1/catalog/{storefront}/stations?filter[featured]=apple-music-live-radio`，Developer Token |
| Personal | `/v1/catalog/{storefront}/stations?filter[identity]=personal`，另需 Music User Token |
| Recent | `/v1/me/recent/radio-stations`，Music User Token，逐頁 25 筆 |

這三者沒有內建電台 ID 或固定播放清單。「為你推薦」與「最近播放」使用目前登入帳號的回應；公開直播／編輯節目屬於 Apple 的地區型目錄，匿名讀取，不宣稱全部都是個人推薦。`MusicServices` 以帳號的實際 storefront 讀取公開頁面；`AppleMusicApi` 每次個人請求讀取當下 Music User Token，storefront 快取也以該 Token 區分。登入完成／登出會清除畫面的電台列及續頁狀態，個人來源空白或失敗時不會補入別人的資料。

44 個電台是 2026-11-01 台灣公開頁 fixture 的解析結果，並非產品寫死的數量。運行時的名稱、身分、順序、封面、區段數及項目數均來自新下載的官方回應；只有端點、可支援資源種類和解析規則寫在程式。自動化測試使用固定 fixture 來檢查真實資料格式，這些 fixture 不會包進產品作為正式資料來源。

只解析直接的 stations 資源，不遞迴擷取關聯歌曲、不把 generated-station continuation 當作 catalog station ID。續頁必須維持原端點、地區及 filter；缺少同一 filter 時補回，不接受換來源或跨站。可選 `radio-show`／動態封面 extension 若收到 400／403，只以相同身分及授權回退基本請求。

官方宣告： [直播電台](https://developer.apple.com/documentation/applemusicapi/get-the-apple-music-live-radio-stations)、[個人電台](https://developer.apple.com/documentation/applemusicapi/get-the-user's-personal-apple-music-station)、[最近播放電台](https://developer.apple.com/documentation/applemusicapi/get-recently-played-stations)、[MusicKit JS](https://js-cdn.music.apple.com/musickit/v3/docs/)。曲風 root／stations 關聯沿用 `CatalogPagesApi` 的正式 station-genres 路徑。

2026-11-01 再次核對上述 Apple 端點頁面的官方 Markdown 原文，Personal／Live 仍使用表中的 `filter` 端點。Context7 的 Stations 主題摘要曾列出 `/v1/me/stations/personal` 及 `/stations/live-radio`，與個別官方端點文件原文不符，因此沒有據此改寫目前正確的路徑。

## 驗證邊界

`apple-music-public-radio.html` 是去除追蹤／播放 actions 的真實頁面縮減 fixture，保留四個區段及 64 個來源項目。五個 wide-only 單集與一個非方形廣播封面保留原始比例（例如 640×160、640×716），以 Fit 顯示；不會只因沒有方形封面就隱藏可用電台。parser 測試檢查完整 44 個電台、直播標題、原順序、實際身分、能力隱藏與不把訪談冒充電台；API 測試檢查授權、extension 回退及 continuation 範圍。整合後的播放、遙控器與首聲延遲以主流程驗證結果為準。

來源稽核新增三項回歸：同一個 API client 更換目前帳號／地區後必須採用新回應；個人／歷史空回應保持空白；公開資料新增／刪除／改名後採用伺服器的新身分及數量。它們驗證資料來源與隔離規則，不宣稱這個帳號當下必有非空個人／歷史列，也不把公開 44 個入口當成個人推薦成功的證據。


Radio 畫面完成載入後最多保留60秒；聽過不同電台後重訪也會更新。各來源回傳時保留已呈現區段與焦點，完成才提交完整結果；取消或來源部分失敗不會把部分結果永久當作完成，並保留既有資料等待下次重訪重試。個人／歷史第一頁更新會重置該來源的舊 continuation，避免新舊游標混用。

## 播放引擎、直播與 FFT

`MusicKitStationPlayer` is a separate official MusicKit JS adapter. Real Apple Music 1 playback, Stop/reconnect and timed title/performer changes have bounded emulator verification; this does not establish every station/device. Radio FFT now has real public audio-graph evidence on the emulator and physical television (2026-11-02). The shipped Android MusicKit 1.1.1 AAR does not expose a station container. This adapter calls the actual supported `setQueue({station: id})` instead of passing undocumented integers to that Android SDK or substituting a song-generated station.

Evidence checked on 2026-11-01:

- Official MusicKit JS URL: <https://js-cdn.music.apple.com/musickit/v3/musickit.js>. The local research-only copy `work/apple-music-web/source/musickit-station.js` has SHA-256 `21908b72bdcdeea3ec91fa40b105d0b9a2ba07c2a3fe32ea77edcd26e735012c`.
- Its writable `musicUserToken` setter is at character offset 407099; it delegates to the library's token store. No new authorization popup or cookie clearing is needed to submit the app's existing user token.
- The same library distinguishes preview, unencrypted-full and encrypted-full playback types (offset 66226) and contains queue next/previous playable indices (426372), live station metadata (71242), and DRM support reporting (566212). These are static observations, not evidence that this emulator can stream a particular station.
- The Apple Music website's existing `source/index.js` uses `MusicKit.setQueue({station: ...})` at the previously recorded playback path near 1687075. Catalog listings distinguish live, broadcast and episodic resources; they cannot all be mapped to a song queue.
- Current API guidance for setQueue, playback states and time/duration was retrieved through Context7 from [MusicKit on the Web](https://js-cdn.music.apple.com/musickit/v3/docs/). Android protected-media permission behavior was checked against [WebChromeClient](https://developer.android.com/reference/android/webkit/WebChromeClient#onPermissionRequest(android.webkit.PermissionRequest)).

The adapter is lazy: merely browsing Radio does not create a WebView. Selection creates one attached, one-pixel, transparent, nonfocusable view with native product controls. It accepts only station requests, retains station identity while reporting the actual item's title/artist, uses generation guards and replaces the old page on a new station so an old asynchronous queue cannot win. Periodic position snapshots are limited to four per second, alongside discrete state changes. Live playback disables seeking; skip controls derive from the real MusicKit queue. There is a 30-second initialization timeout, renderer-loss handling, and cleanup on release. Native song playback must be paused before selecting this engine; the engine must be released before returning to native songs. The application must route listeners by the active engine, because request generation counters are engine-local.

The embedded page loads Apple's unchanged library from Apple's CDN. No media URL extraction, DRM alteration, subscription bypass, preview-as-full fallback, or downloaded/re-hosted Apple implementation is included in the product. DRM-unsupported and preview states are rejected. Protected-media permission is scoped to the app bootstrap origin and only that requested resource; camera and microphone permissions are not granted.

The station adapter uses WebView for official MusicKit JS playback; phone sign-in is a separate flow. Route availability does not guarantee account authorization, EME support, later streaming continuity, audio focus or device performance. A bounded successful station experiment does not establish every broadcaster or device.

### Live Stop and reconnect

The bounded live experiment reached actual MusicKit state `playing` with `isLiveStream=true` and an advancing backing-player position. It also exposed a genuine transport difference: `ContinuousPlaybackController.pause()` in the same official JS at character 459844 deliberately warns and resolves without pausing a live station. Its class begins at 458902 and reports PAUSE/SEEK/SKIP unavailable for live playback. Calling `pause()` and accepting the resolved Promise was therefore incorrect.

The adapter now routes the user's live Pause to official `music.stop()`. The corresponding source chain is `MKInstance.stop` (577399), `PlaybackController.stop` (447565), `BasePlayer.stop` (195357), and `stopMediaAndCleanup` (195764), which stops the media element, clears the current item, and reports `stopped`. The adapter reports that real stopped state and `isPrepared=false`. A later Play waits for any in-flight Stop, re-queues the **same station** with `startPlaying:false`, and reconnects at its live edge; it does not seek or replay an old buffer. Native and JS intent guards invalidate older queued Play/Pause commands. Incoming live Playing after a newer Pause triggers a bounded Stop, with one attempt per intent or fresh Playing transition, rather than an unlimited stop loop. A terminal error destroys the backing page before publishing a stopped/error snapshot, so a rejected Stop cannot leave audio running behind a paused UI.

A live HLS duration is a moving buffer-window length, not programme or track duration. Live snapshots now expose `durationMs=0`, retain the actual backing position, and keep seeking unavailable. The raw window duration remains an internal diagnostic field. Global DRM capability reporting alone does not reject an unencrypted live stream; a real preview fallback still fails.

On 2026-11-01 the emulator observed real Apple Music 1 Playing progress, Stop (state 4 with no current item), and same-station reconnect (state 2). The reconnect initially lost its prepared flag when setQueue briefly reported the previous Stopped state; that handoff was repaired and revalidated. A separate bounded Media Pause check passed and left the backing stream stopped. Raw failed trials are retained; UI/SDK state and progress are not a claim that every station, device or later DRM key period was tested. See `work/phone-login/20261101/verification.md`.

### Numeric storefront IDs in timed metadata

The 2026-11-02 live diagnostic found `storefrontAdamIds` keyed by numeric iTunes storefront IDs, while the independent JS instance defaulted to country `us`. Treating that dictionary as ISO country keys made every real song-art lookup miss. Real title changes still worked, which is why a Playing-state test alone did not catch it.

`app/src/main/assets/apple-storefront-ids.json` contains the 174 country/ID records retrieved on 2026-11-02 from Apple's [available storefronts response](https://itunes.apple.com/WebObjects/MZStore.woa/wa/availableStoreFronts). The mapping is protocol data; Radio contents still come from the account/catalog APIs. The [Apple country/storefront appendix](https://www.apple.com/itunesaffiliates/US/2009/Document/LinktoiTune.pdf) independently confirms US143441, TW143470 and JP143462. The raw first-party response hash and generation proof are saved in `work/navigation-return/20261102/apple-storefront-proof.json`.

The native caller supplies the account's cached or freshly authenticated storefront through the reserved playback dispatcher, without a configured-region fallback. The required country is passed into `MusicKit.configure({storefrontId: ...})` and the timed-metadata lookup; neither route trusts the separate JS instance's startup default. Matching only an ID published for that same region preserves exact-song lookup and avoids title guessing. The bundled mapping avoids extra network work on playback. Benchmark-only diagnostics expose field/key names, lookup status and booleans; tokens and complete metadata are never logged.

### Radio FFT

Radio analysis is sourced from the station's own media element, not the microphone or system mix. The initial public captureStream route was rejected because MusicKit attaches MediaKeys even on this live element; the browser's rejection was respected. The same-origin MSE element supports the public MediaElementAudioSource API, verified with real nonzero48kHz samples on both target devices. No key, media source, credential, licence or protected-media setting is altered. Relevant platform behavior: [Chromium captureStream guard](https://raw.githubusercontent.com/chromium/chromium/main/third_party/blink/renderer/modules/mediacapturefromelement/html_media_element_capture.cc), [MDN source routing](https://developer.mozilla.org/en-US/docs/Web/API/AudioContext/createMediaElementSource).

Each element binds once through a WeakMap. Source→Analyser→destination retains audible passthrough when visual sampling is disabled; actual pause/emptied suspends its context, Playing resumes it even outside Now Playing, and terminal/page teardown releases owned graphs. Spectrum observations are20Hz/1024 FFT with three frequency bands and an independent true-silence RMS gate. A dedicated512-character bridge avoids playback/UI publications per FFT frame; native validation includes epoch/intent/finite values and250ms expiry.

`work/radio-fft/20261102/README.md` records sustained real signal, Pause→zero, resumed signal, and background audio clock advancement with FFT disabled. Five-second signal counts were51/50 on emulator/TV. These are bounded actual playback checks, not a claim that every broadcaster/device was tested.
