# Public Apple Music New fixture

Source: unauthenticated `https://music.apple.com/tw/new`, captured 2026-10-30.
Original HTML SHA-256: `eb34c8d395195e8320b6d47167b4b383b5929e684f7bf1c5492dbe843dcdcef5`.

The fixture preserves all 14 server sections, actual item display fields, canonical content descriptors, artwork dictionaries, header titles and real RoomPageIntent targets. It removes playback/segue metric instructions, impression metrics, unrelated page metadata and any account state. `userTokenHash` is an intentionally ignored fixture sentinel. The `<img>` URLs are the actual square renditions present in the same original HTML; they are not reconstructed from the 4:1 source canvas.

The source hero is a headerless flowcase with 4:1 artwork and remains excluded. Standard named shelves preserve their source order. Default capabilities produce 10 shelves and 170 items. The public HTML only pre-renders square covers for 7 Daily Top 100 and 7 City Chart items; the remaining wide-only items are omitted instead of being arbitrarily cropped. The station capability adds its 20 actual station items. No fixture ID is hard-coded in product code.
