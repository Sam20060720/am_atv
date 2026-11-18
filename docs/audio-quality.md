# 音質與 Dolby Atmos

經典和 Apple 介面共用音質設定。變更會在下一首或重新播放時生效。

| 設定 | 播放方式 |
| --- | --- |
| 高音質 AAC（預設） | 使用 MusicKit 的一般播放方式。 |
| 高效率 AAC | 從可用的 AAC 音軌選擇宣告頻寬最低的一條。找不到時使用一般 AAC。 |
| Dolby Atmos（預設關閉） | 歌曲提供 E-AC-3 JOC、裝置支援該格式時選用 Atmos 音軌。其餘情況使用 AAC。 |

無損與高解析度無損目前無法選取。

## 排查播放問題

- `AppleAudioAssetResolver` 以帳號地區和歌曲的 `playParams` 找音軌。預設 AAC 由原生 MusicKit 播放。需要選擇音軌時，`MusicServices` 將可用的 HLS 音軌交給 `AppleHlsPlayer`。
- HLS 播放失敗時，同一次播放準備最多改用原生 MusicKit 一次。切換時沿用最近的位置、待播清單及使用者最後選擇的播放或暫停狀態。
- 確認 Atmos 輸出時，在相容的電視和音響上播放有 Atmos 的曲目，並查看接收端顯示的音訊格式。App 的音軌選擇可用來排查，實際輸出以接收端為準。
