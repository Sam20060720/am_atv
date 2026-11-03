# Apple TV 17.0.0 UI evidence

> 2026-11-04：一次性解包樹與舊測試擷取已清理；下文的原始檔路徑、行號及位移保留作研究時的來源定位。

Research date: 2026-10-27. Scope: static inspection of the supplied Android TV package. These are shipped declarations and code paths; they do not establish how a particular TV rendered the app or which server-driven page variants were returned.

## Reproducible source identity

- Supplied APKM: `tmp/com.apple.atve.androidtv.appletv_17.0.0-170000_2arch_9db5c94867c43df949260b84a160eec1_apkmirror.com.apkm`.
- SHA-256: `23340ef8643f6c2b469edc9dc3e72b3e8b181afaf050b73b7137ae2b563c1260`.
- REA artifact inventory: `ev_846d277c673e6168bef52290a0fb25b65a7b83c876aa04df3092de9e5e598cba`.
- REA extraction: `ev_e0ab35f07b462c055b0abbe27b1791bc49ac4ca2d4718e7ededb303b56752856`.
- Local research tree: APK resources in `tmp/appletv-decoded`; decompiled Java in `tmp/appletv-jadx`; extracted `assets/app.zip` in `tmp/appletv-js`.
- `app.js` SHA-256: `0ff15786a4e1379dd765f31493e236b0892b16e7abe23a01723d8b34bb716361`.
- `SuperBrowsePage.js` SHA-256: `110b1cfab2dbb44498f69706df516c9b6a40a754b67e94722e3487493d2ff217`.

Locators below are zero-based Python string character offsets in those exact minified files, followed by searchable function/module names. These are not byte offsets. Extracted vendor sources are research inputs, not product dependencies.

## Rendering architecture

Observed: the Android resources primarily contain shell/AppCompat assets. The real interface is the JavaScript application inside `assets/app.zip`, drawing through Luna `Gfx.DrawingContext`. Java exposes native `drawFrame()` and `initLuna(...)` in `com/apple/atve/luna/Native.java:126` and `:139`; `MainActivity.java` sets up Luna composition and separate video surfaces.

Implication: moving Android layout XML or isolated images does not transplant the official UI. Reimplement the observed geometry, states and transitions in AMATV's existing Compose renderer. Bringing the complete proprietary JavaScript/native runtime would also bring its application services and lifecycle rather than a standalone view library.

## Coordinates and typography

Observed in `app.js`, module `46491`, offset `1908402`: `yA` scales design coordinates by `sqrt(displayWidth * displayHeight / 2073600)` and rounds by default. Thus 1920×1080 is the design basis, not Android dp. Safe area uses horizontal 80 and vertical 60 design pixels. Text-size tiers change several component dimensions.

For an AMATV layout whose Compose viewport is 960×540 dp, half of the design value is a useful starting point. This is a layout adaptation, not a claim that vendor coordinates are Android dp.

## Floating navigation: pill to expanded panel

Observed in `SidebarMenu_SidebarMenu` at `app.js` offsets `2472000`–`2497400`:

- Explicit states: `StartUp`, `Hidden`, `Pill`, `Expanded`.
- Collapsed navigation is a top-leading current-page pill; it is not a permanent full-height icon rail.
- `calculateSize`, offset `2492408`, interpolates position, width, height and radius between the pill and expanded panel. Panel height is capped and otherwise follows menu content.
- Right navigation from the expanded sidebar collapses the menu and selects the content deck. Header and list become nonfocusable after collapse completes.
- Expand and collapse use `spring(target, 25, 185, 1)`. The animation implementation at offset `2647993` establishes the argument order as damping, stiffness, mass. A comparable unit-mass Compose damping ratio is `25 / (2 * sqrt(185)) ≈ 0.919`; this conversion is an implementation inference.
- Reduce Motion or disabled sidebar animations selects the final state immediately.
- A selected page with its own sidebar can add a 200 ms expansion delay. Inactivity timeout is 60 seconds; the introductory teachable moment uses 8 seconds.

Observed regular text tier L defaults in module `21273`, offset `2497800` onward:

| Property | 1080 design value | 960×540 dp starting point |
| --- | ---: | ---: |
| Expanded panel width | 358 | 179 |
| Expanded panel outer inset | 35 | 17.5 |
| Expanded panel corner radius | 52 | 26 |
| Expanded height cap | 1010 | 505 |
| Menu item width | 320 | 160 |
| Menu item height | 66 | 33 |
| Menu item radius | 33 | 16.5 |
| Menu item gap | 10 | 5 |
| Pill outer inset | 59 | 29.5 |
| Pill height at regular text scale | 60 | 30 |
| Pill icon | 50 | 25 |

Panel background uses dark regular material when enabled, otherwise a SystemGray5 color; shadow is black with opacity 0.4, y offset 4, blur radius 48. The minimum pill scale is 0.8. Menu focus uses a white 90% fill and reversed dark label; idle fill is transparent with white 80% labels. Menu item focus timings are explicitly overridden to zero, independent of the panel spring.

## Focus, image cards and grids

Observed generic selectable defaults in module `31210`, offset `2447917`:

- Focus entry 85 ms, focus exit 230 ms, press and release 80 ms each; specific components may override these values.
- Default focus zoom increases width by 50 design pixels, and absent an explicit vertical zoom the height follows the width ratio. This is not a universal fixed scale such as 1.03.
- `SelectableWidget.animateFocusChange` at offset `2441000` uses ease-in on entry and ease-out on exit. It forces immediate motion when the performance profile disables focus animation or accessibility Reduce Motion is active.

Observed image styles in module `10751`, offset `1566331`:

- Image fade-in: 240 ms when other animations are enabled, otherwise immediate.
- Idle shadow: y 4, black 40%, blur based on card corner.
- Focused shadow: y 40, black 30%, blur 50.

Observed grid defaults in module `301`, offset `2252403`:

- Grid widths derive from the safe area and column count, not a single fixed card width.
- Horizontal gap 40; common shelf vertical gap 109, library square grid vertical gap 104.
- Common 2/3/4/5/6-column corners: 28/24/20/20/18 respectively. General corner vocabulary also includes 0, 16 and 32.
- The library square grid is five columns with radius 24. Film rows generally use 16:9 images; music artwork should keep its own square aspect ratio.

## Hero/background and content replacement

Observed shelf geometry selector around `app.js` offset `401000`: epic stage and showcase-wide treatments use safe-area-wide content, height 636, horizontal gap 40 and vertical gap 190; stage radius is zero and showcase-wide radius 30. These are alternative shelf types, not proof that the user's server response selects either one.

Observed slideshow code around offsets `1816600` and `1837800`: replacement background artwork is prepared before a crossfade, and the previous background is disposed after the transition. Automatic advance requires foreground lifecycle, controller permission, enabled performance feature and disabled Reduce Motion. Video eligibility additionally requires the active slide to match the current slide.

Observed `SuperBrowsePage.js`, module `38673`, offset `120386`:

- Focus-dependent content debounce: 300 ms.
- Title out/in: 150/200 ms.
- Content scale sequence: 0.98 over 170 ms, then 1.03 over 150 ms, settling over 430 ms; slots stagger by 100 ms.
- Initial fade delay 150 ms and fade duration 200 ms; shelf fade 500 ms.

The debounce, retained image ownership and foreground checks are useful performance patterns. Reproducing every backdrop as a live shader or video would be an implementation choice, not a requirement established by this evidence.

## Official performance tiers

Observed in `ApplicationConfig_ApplicationConfig.getPerformanceProfile`, `app.js` offset `59300`:

| Feature | Static enable condition |
| --- | --- |
| Focus/other animations, auto scroll, slideshow advance, frequent play-position updates | Above LOW |
| Menu animations | Above MEDIUM |
| Page transitions, real-time blur, product card view, tabbed-menu animation, portrait/background video positioning | ULTRA or higher |
| Glimmer placeholder | HIGH or higher |
| Sidebar animations and materials | Enabled by this profile; still subject to accessibility checks |

Inference for AMATV: keep layout and focus semantics consistent across device classes; vary costly rendering work. The package itself does not attempt to run every effect identically on its lowest performance tier.

## Remaining unknowns

- Static research was followed by an emulator launch. The official package displayed its splash, then an incomplete scene with missing text/artwork. This is insufficient for a pixel comparison or a complete menu/player interaction claim; actual device-selected performance level and remote page variants remain unknown.
- Dwell and SuperBrowse are separate components; their existence does not establish that every home page uses those transitions.
- Vendor design-coordinate constants and spring parameters do not guarantee a pixel-identical Compose result. Check screenshots and D-pad behavior at the actual viewport and measure the resulting frame work.
- Account, search, detail, notification and playback pages have not each been comprehensively mapped. This evidence supports the first navigation/browse update, not a claim of complete UI equivalence.

## Runtime follow-up

On 2026-10-27 the unmodified base and arm64 split were installed on the existing API 34 Android TV emulator. The splash and an incomplete dark scene rendered, but the home feed and usable labels did not. No account was entered. Local captures are in `work/apple-tv-ui/appletv-start.png` and `appletv-runtime.png`. Do not interpret the incomplete scene as the intended official home layout. The TV on the local network had no existing `com.apple.atve.androidtv.appletv` installation; it was not modified for this reference check.

REA JavaScript graph analysis returned `unreadable_output`; the detailed JavaScript findings above come from targeted local source inspection, not a successful REA semantic graph. The REA inventory and extraction evidence remain valid.

## Playback controls: focused static comparison

The following findings concern the shipped `PlayerPage.js` control layer, not lyrics, music artwork layout or the AMATV player background. No product source was changed by this research pass. References are zero-based character offsets in the extracted minified files; all dimensions below use the same 1920×1080 design coordinate basis described above.

`PlayerPage.js` SHA-256: `f61f7ecc30ed84b14a39a27e0cf3e17cef1b4d1d7c378cd9262bd290a8c5a862`.

### Transport geometry and progress

Observed in `PlayerPage.js`, module `29` (starts at offset `337405`, geometry at `341500`), and `UnifiedPlayerWidget` layout at offsets `305100` and `308979`:

| Control geometry | 1080 design value | 960×540 adaptation |
| --- | ---: | ---: |
| Left/right content safe area | 80 each | 40 dp each |
| Bottom padding | 50 | 25 dp |
| Progress component slot height | 48 | 24 dp |
| Progress to bottom menu gap | 17 | 8.5 dp |
| Top title/menu row to progress gap, remote | 22 | 11 dp |
| Top title/menu row to progress gap, touch | 15 | 7.5 dp |
| Icon controls spacing | 25 | 12.5 dp |
| Bottom tab spacing | 24 | 12 dp |
| Anchored floating menu offset above its button | 20 | 10 dp |

Observed actual bar style in module `13532`, offset `299300`, drawing at `267158`, `278593` and `283000`:

- Bar height 14, corner radius 7. This is independent of the outer 48-high progress slot.
- Color A is RGB 244/244/244 with alpha 0.56; B is RGB 235/235/235 with alpha 0.40; C is RGB 236/236/236 with alpha 0.26. Reduced Transparency replaces C with opaque RGB 128/128/128.
- In VOD, idle played and remaining segments both use C. Focus changes the played segment to A. A pending scrub position introduces a B segment between the current and requested positions. The moving playhead is opaque white.
- Remote-only playhead is rectangular: idle width 1 × height 14, focused width 2 × height 16. Drawing clamps its x coordinate to the bar. The focused head extends 1 design pixel above and below the 14-high track.
- A round playhead is selected only when pointer or touch support is present and the stream is seekable/VOD. Its diameters are 14 idle, 20 focused and 26 while dragging/within the drag zone. A permanently large round knob is not the observed remote-only default.
- Current and remaining time use tabular emphasized Caption2, size 23 and line height 30 at regular text scale. Focus color is white; idle is white 50%.
- The current-time label follows and centers beneath the active playhead, clamped within the transport bounds and trickplay-icon insets. It is not fixed to the left edge. The remaining-time label is right-aligned with inset 5 and omitted if it would collide. Their baseline is derived from bar height 14 plus baseline spacing 32, with the text ascent applied when drawing.

### GlassPlayer icon and tab buttons

Observed in `app.js`, module `41457`, offsets `2309447`–`2322450`; `PlayerPage.js` module `29` selects `RegularEmphasized.GlassPlayer` for icon controls and `Regular.GlassPlayer` for labeled tabs. The icon factory at offset `315900` explicitly sets width equal to control height.

| Property, regular text tier L | Shipped value |
| --- | --- |
| Icon button frame | 66 × 66 design pixels (33 × 33 dp adaptation) |
| Corner radius | ceil(height / 2), giving 33 (16.5 dp) |
| Focus horizontal growth | 8 design pixels (4 dp); height follows the scale ratio |
| Focus entry / exit | 75 / 150 ms |
| Idle fill | RGB 30/30/30, alpha 0.9 |
| Idle outline | White, alpha 0.16 |
| FocusCandidate fill | Gray49, alpha 0.8 |
| Focused fill / foreground | White 90% / reversed dark label |
| Idle foreground | Primary light label |
| Labeled tab horizontal padding | 30 on each side |
| Regular label typography | Body, size 29, line height 36 |

These are individual pill/circle styles. The source does not establish AMATV's existing shared white-12% background and 1 dp border around each entire button group as an official transport treatment. Button material here is an explicit translucent color and outline; copying it does not require a live backdrop blur.

### Show, hide and focus behavior

Observed in `UnifiedPlayerWidget.hide/show/restartTimer`, `PlayerPage.js` offsets `329500`–`331900`:

- Whole-control opacity enters and exits linearly over 300 ms. This path does not slide the controls vertically.
- Automatic/player activity hide delay is 5 seconds. Paused, tab/control focus, visible tips and open control menus use 8 seconds. Opening a content tab uses 20 seconds.
- Screen-reader/always-on virtual-view conditions can double those delays. Active dragging, fullscreen trickplay and thumbnail scrubbing suppress automatic dismissal.
- Scrubbing hides other controls with a separate linear 250 ms fade (`updatePlayerUiWhileScrubbing`, offset `327900`). Thumbnail presentation, when present, uses ease-out 200 ms on show and ease-in 200 ms on hide (offset `263211`).
- `reset()` selects the progress transport by default. Remote progress Up enters the top control row; Down/Info enters the lower tabs. Menus open anchored above their control with the offset noted above and restore focus when closing.

### Remote/touch branch and AMATV mapping

Observed at `PlayerPage.js` offsets `322800`–`323850`: the non-touch television layout has title on the top-left and menu icon controls on the top-right, then the progress bar, then tabs on the bottom-left and toggles on the bottom-right. `createPlayerControls()` at offset `335797` creates rewind / back 10 / play-pause / forward 10 / fast-forward, but is called only in the touch-enabled branch at offset `323457`. Thus the existence of these five icons is not evidence that the Android TV remote layout always displays a central transport-button cluster.

At the time of comparison, AMATV's relevant implementation is `PlayerScreen.kt`:

| AMATV area | Existing state | Evidence-backed change boundary |
| --- | --- | --- |
| Control container, around lines 675–799 | 88/84 dp side insets; shared group platters; 280/360 ms fade plus vertical slide | Match safe insets, individual GlassPlayer styling and 300 ms opacity without modifying the lyric stage |
| `Progress`, around lines 826–948 | Focus changes track thickness 4→8 dp; large round thumb; elapsed fixed left; pure-white played track | Port fixed 7 dp bar, remote rectangular playhead, A/B/C state colors and playhead-following elapsed label |
| `PlayerButton`, around lines 952–999 | 46/56 × 40 dp, fixed scale 1.08 and 180 ms focus tween | Use 33 dp regular GlassPlayer geometry, width-derived growth and 75/150 ms focus timing, preserving music actions/semantics |
| Visibility timer, around lines 332–345 | 5-second common timer | Apply reason-specific delays only if keeping the existing music navigation contract clear |

The mapping is an implementation recommendation, not proof that a music player with previous/next-song actions is identical to Apple's film/video player. Song/queue/lyrics functionality should remain controlled by AMATV's existing state machine. Exact glyph shape, font rasterization, remote device feature flags and final pixel equivalence still require a usable official runtime reference.

## Bundled fonts and independent vector resources

Bounded inventory of the complete supplied base APK and both ABI splits found 980 regular files in the base APK and 8 in each split. The splits contain three native libraries each and no asset directories. The actual `assets/app.zip` contains 248 regular files, including 107 under `Assets/`, 30 WOFF and 10 WOFF2 files. The `Assets/` directory is not empty: inspecting only its directory entry or the beginning of the ZIP listing misses the nested resources.

The UI font loader at `app.js` offset `2243300` defaults to `Assets/atve-kit/Fonts/SFHello-{weight}.woff2`, builds a native font list, then adds script fallback paths and the Helvetica Neue / Apple Symbols / Zapf Dingbats symbol subsets. Rounded text explicitly selects SFCompactRounded. These are independent font assets; using the four SFHello faces below does not require copying the Luna native renderer.

### Scoped SFHello resources

Converted with FontTools 4.67.0 plus Brotli. SFHello's original WOFF2 wraps an OpenType/CFF (`OTTO`) font, so the output uses `.otf` rather than mislabeling it as a TrueType-outline `.ttf`. Conversion removes only the WOFF2 wrapper: `TTFont(source, lazy=True, recalcTimestamp=False)`, `font.flavor = None`, `font.save(output)`.

Verified each output's `CFF ` outline table, `cmap`, `name` metadata and `hmtx` metrics are byte-identical to the source reader's decompressed tables. Each face has 2,731 glyphs and 1,741 Unicode mappings. Original copyright and license name records remain present, including the SFHello name-ID 13 statement referring use to the applicable iOS Software License Agreement; this conversion does not alter those records.

| Face / product resource | Source WOFF2 SHA-256 | Converted OTF SHA-256 | OTF bytes |
| --- | --- | --- | ---: |
| Regular / `res/font/tv_sfhello_regular.otf` | `107adad3b3d015bc933a56a9b8ebcdadadacb03fa6b0564d69d9207f4137c0f6` | `17a9a803fe3db48bcc832c3dda96f6dbafb9ed1435dec84b9cb71662572b852f` | 440920 |
| Medium / `res/font/tv_sfhello_medium.otf` | `b6a9720700b30d9d187bb686ac771f34b5f3ccdaab6fc07d80a8c4cb3c3c40df` | `af3df7b6577305f61988bdea5691122b6f9af8201b91d109cfd04c88a16f45aa` | 438560 |
| Semibold / `res/font/tv_sfhello_semibold.otf` | `e3c79731c0b4638323e81d6bfa49f8616b4f1e5db3c62cbc1db42ae66331f103` | `1b29b8e77c8c4646326dee5fd7963690a341f9a5e561eb5285e8f91ce0220afd` | 441760 |
| Bold / `res/font/tv_sfhello_bold.otf` | `5e31c87a0bd5ecd35a43d5cd4be30436ee783faa77790b0c907beb03a71a20bb` | `587a94f95cf92392766b5bf98ac4fd9f9f9a933d4e56bbb1f796cc74fa1dc618` | 443160 |

`TvTypography.kt` provides a scope that replaces only the font family in all 15 existing Material3 typography roles and the inherited `LocalTextStyle`. It preserves existing sizes, line heights, weights, spacing and other style attributes. Its application belongs to the browse/control viewports; the lyric subtree must retain its existing typography scope. Missing scripts continue to use Android fallback.

### CJK and icon coverage limits

Actual font parsing found SFHello has no glyphs for the sample Chinese navigation labels. The bundled `PingFangTC-Semibold.subset.woff` contains only 29 glyphs / 8 Unicode mappings: the characters in `繁體中文（台灣）`. `PingFangSC-Semibold.subset.woff` similarly covers the language-name sample, not a full Chinese UI. These subsets were not added as product CJK fonts. The native loader's script fallback list, rather than these tiny subsets alone, supplies the rest of the official CJK interface.

`AppleSymbols.symbols.subset.woff` parses as Apple Symbols, with 2,629 glyphs / 2,620 Unicode mappings. It includes U+25B6 (play triangle) and U+266B (music notes), but the tested U+23F8 (pause), U+23ED/U+23EE (next/previous), and U+1F50D (magnifier) are absent. It is therefore not evidence of a complete SF Symbols icon font for the app's controls.

No standalone SVG files occur in the full `app.zip` inventory. Many vectors are embedded directly in JavaScript path/size objects. `Image.symbolWithSize` and `Image.symbol` at `app.js` offsets `2186396` / `2186655` construct `Gfx.SvgRoot` from those paths, independently of font glyphs. `PlayerPage.js` module `29` has explicit semantic keys `PlayIcon`, `PauseIcon`, `ForwardIcon`, `Forward10Icon`, `RewindIcon`, `Rewind10Icon` at offset `343842` onward; those paths can be mapped without guessing. No explicit previous-song/next-song semantic keys were found in the bounded named-icon search across `app.js` and `PlayerPage.js`; video fast-forward/rewind should not be silently labeled as song skipping.

Navigation icons also have a server-driven path: `app.js` offset `1175605` chooses regular/bold images and decodes `embeddedSvg` supplied in tab data. The offline package alone does not establish every exact current navigation icon. Retaining a Material icon there leaves a known glyph-shape difference; this is separate from the now-available SFHello typography assets.

### Extracted playback vectors

`TvPlaybackIcons.kt` exposes `TvPlaybackIcons.Play` and `.Pause`. The path strings were extracted from the explicitly named `PlayIcon` / `PauseIcon` factories in `PlayerPage.js`, module `29`, and verified identical after Kotlin string serialization. Source file SHA-256 remains `f61f7ecc30ed84b14a39a27e0cf3e17cef1b4d1d7c378cd9262bd290a8c5a862`.

| Vector | Source character offset | Exact path-string SHA-256 |
| --- | ---: | --- |
| Play | 344011 | `e9925c2f6295f0eb0e1d72a7658ba3e8d5e0f3a2ade1af95e1e2079a2c321cb0` |
| Pause | 344306 | `8982c1695deef6cbcf84b354a577df1439cd300d5a8689d88c70676ce5ef9ed6` |

Both vectors preserve the complete original 64×64 viewport, path coordinates, default nonzero fill rule and internal whitespace/bounds. The source factory creates a 64-design-pixel image, so the Compose default is 32 dp at the 960×540 control viewport. Render them at that size to preserve the original glyph-to-button ratio; reducing the whole icon to 22/24 dp also scales its original internal margins.

The bounded semantic-name search across `app.js` and `PlayerPage.js` found no `LyricsIcon`, `QueueIcon`, `queueIcon` or `QueueMusic` keys and no previous/next-song factory. The confirmed video `ForwardIcon` / `RewindIcon` and ten-second seek factories were not relabeled as music track skipping. Only Play/Pause were added; the music-specific controls retain their existing icons.

## Photo reference update: Apple Music collection view

The user subsequently supplied three photographs of Apple Music on Apple TV. These supersede the Android Apple TV video app as the visual target for the music interface. For the collection revision, the second photograph was relayed as: large square artwork at the left occupying approximately 40% of screen width; descriptive content at the top-right; a play pill and a separate circular shuffle control; song rows with square thumbnails, two text lines and right-aligned durations; a translucent light rounded focus row whose text remains white. This implementation pass received that reviewed description, not a local photograph file, so the proportions below are estimates rather than pixel measurements.

`CollectionScreen.kt` previously reserved a fixed 270 dp left column for artwork, metadata and two equal-width rectangular actions, leaving a separate list to the right. It now reserves 43% of its inset content width for the square cover (approximately 39% of a 960 dp screen), capped by available height, and places the existing metadata, play pill, independent 42 dp shuffle circle and song list together in the right column. Album and playlist rows both show 38 dp square artwork; the whole-row focus remains translucent white with white text. Existing action IDs, D-pad focus requesters, playback selection, lazy item identity and loading/retry behavior are retained.

At this initial photo adaptation, the data models did not yet expose an editorial description. Later catalog work added the real API description: it now scrolls with the title/actions and songs when available, without fabricated fallback text. The photograph does not establish the complete page header, so no additional header or navigation chrome was invented. The collection selects its own artwork through `LocalBrowseBackdropController` for the shared ambient background; this file does not create a separate full-screen decoder, video or blur layer. Lyrics are outside this collection view and were not modified.

## Current cover and collection drawing rules

The supplied `app.js` module10751 at offset1566331 supplies `outlineColor: () => Q1.borderColor()`. `BaseLockup.outlineColor()` at1571337 uses that theme value; module44548 at2172202 resolves it to `colors.hairline`. Matching theme locators2178314/2179615 declare light black10% and dark white8%. The outline is one design pixel, or0.5dp on AMATV's normalized viewport, and does not brighten with focus. This does not establish that every tvOS card has the same outline.

Only rounded artwork owns the soft shadow:12dp blur,2dp downward offset, opacity0.16 at rest/0.38 focused. There is no local rectangular shadow clip; Home rows reserve20dp vertically and Library grids reserve drawing clearance. Caption wrappers have zero elevation. Pointer hover remains distinct from remote input focus.

Collection detail keeps the left artwork stationary while title, real description, actions and songs share one lazy right viewport. Its20dp edge fade and24dp reveal margin do not create another scrolling pane. Global navigation stays above the captured page and returns to the retained item.

Implementation references: [Compose shadows](https://developer.android.com/develop/ui/compose/graphics/draw/shadows), [lazy layout keys and scrolling](https://developer.android.com/develop/ui/compose/lists), [FocusRequester](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior), [Apple editorial formatting](https://developer.apple.com/documentation/applemusicapi/editorialnotes). The photo proportions remain estimates, not calibrated pixel measurements.

Superseded visual experiments, candidate APK hashes, test counts and temporary manual-review instructions are retained in ignored `work/repo-review/20261104/archive/docs/apple-tv-ui-evidence.md`. Current behavior is summarized in [apple-tv-ui.md](apple-tv-ui.md); no historical candidate result establishes current full-app or physical-TV60fps.
