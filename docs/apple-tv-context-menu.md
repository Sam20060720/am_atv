# Apple TV contextual menu reconstruction

The supplied Apple TV Android package ships a Luna `FloatingMenuWidget`, not an Android default dialog. AMATV reimplements the observed declarations in Compose and uses a Dialog window only for modal focus and text input. No Luna runtime/source is imported into the app.

## Artifact and evidence boundary

- Input: the user-supplied `tmp/com.apple.atve.androidtv.appletv_17.0.0-170000_2arch_9db5c94867c43df949260b84a160eec1_apkmirror.com.apkm`; extraction identity is recorded in [apple-tv-ui-evidence.md](apple-tv-ui-evidence.md).
- Inspected extracted source: `tmp/appletv-js/app.js`.
- SHA-256 rechecked: `0ff15786a4e1379dd765f31493e236b0892b16e7abe23a01723d8b34bb716361`.
- Locators below are **zero-based character offsets in the UTF-8-decoded app.js**, plus stable module IDs/search tokens. They describe static declarations and branches, not observed native rendering.
- The REA extracted-JavaScript analysis returned unreadable output on its initial request and the prescribed retry. No graph or REA Evidence ID is claimed for these findings; source locators and the matching hash are the reproducible evidence. No native session was opened or application executed in this pass.

## Findings

| Question | Shipped source locator | Observation |
| --- | --- | --- |
| Which component is used? | `1127600`, contextual action builder; `2359262`, module `51690`; `2359088`, module `83921` | The action builder creates buttons/separators in an AlertPage with Black30, opens `FloatingMenuWidget` in Contextual mode with the invoking widget as parent, disposes the alert after closing, then invokes the selected action. Empty action collections do not open. |
| How is it anchored? | `2307319`, module `12364`, `TopTrailing` / `TopLeading`, `Dj` | Parent top-trailing to child top-leading, gap 20 design pixels. A horizontal flip and then vertical flip are accepted only when they fit; otherwise the original anchors remain and final position is clamped. The selectable widget's zoomed rectangle is used. |
| What are the normal dimensions? | `2379669`, module `46261`, `width`, `maxHeight`, `cornerRadius`, `horizontalPadding` | Width 454, max height 600, radius 54, horizontal/top/bottom padding 21, item spacing 4 design pixels. Accessibility declarations also include width 567.5 and max height 750. |
| How are buttons drawn? | Same module, `buttonStyle`, `selectableButtonStyle` | 66 design-pixel capsule; 86 for measured two-line titles; leading slot 66, icon height 27; selected leading checkmark height 20. Focus enters in 100 ms and leaves in 200 ms. Hover candidate uses White15; focus uses opaque Gray92, reversed text and shadow. Horizontal expansion is 8 design pixels. |
| What typography is declared? | `2245625`, module `3038`, `Standard.Caption1` / `Caption2` | Caption1 is Medium, size 25, leading 32; Caption2 is Medium, size 23, leading 30, before design scaling. |
| What separates groups? | Module `46261`, `separatorStyle` | Height 1, horizontal inset 12 and vertical inset 16 design pixels; White10. Heading support exists (Caption2 White50, height 59), but the inspected contextual builder does not add a large artwork/title header. |
| What material/shadow is declared? | Module `46261`, `backgroundColor`, `backgroundMaterial`, `shadow`, `border`; module `51690` material branch | Regular.Dark material when allowed by performance/reduce-transparency conditions; fallback RGB(30,30,30,.9), with an opaque material fallback. Shadow Black30, radius 30 and offset [0,10]; theme hairline width 1 design pixel. |
| What is the open/close motion? | `2376460`, module `51690`, open/close; module `46261`, `animation`; `2647633`, `easeOut` | Open opacity 0→1 and scale .35→1 in 380 ms about the resolved anchor; close reverses in 210 ms, disables focus, then hides. The shipped easeOut function is `t * (2 - t)`. Reduced motion omits scale; forced-instant mode skips animation. |
| How do inline submenus behave? | `2372500` / `2373386`, module `51690` | A 150 ms outgoing phase and 150 ms incoming phase, horizontal offset 50 design pixels, reversed when going back. Collection and focus selection are replaced between phases. |
| How do keys behave? | `2368004`, module `51690`, `createButton`; `2370699`, `getItemHeight` | Actions activate on released state; Right release opens a submenu; inline Left release returns. Back pops the menu stack or closes the root. Accessibility navigation does not wrap. |
| What happens during size/scroll changes? | `2366278`, module `51690`, `updateContainerRect`; module `46261`, `fadeOut` | Container geometry uses a 200 ms easeOut transition and a 100 ms content fade. Edge style declares inner 62, scroll fades 22/20, and lower clip expansion 40 design pixels. |

### Focus motion versus a fading selection trail

The same app.js hash was rechecked for the later report of menu-row afterimages. `ButtonWidget.buttonView` at character `2332364` reads `getStyleForCurrentFocusState()`; the latter at `2338301` selects `Focused` / `FocusCandidate` / `Idle` directly. `getBackgroundView` at `2342487` uses that current style's background and enables the shadow only when that style declares `useShadow`. The independent dynamic zoom is used for content/background geometry and focused shadow interpolation. `animateFocusChange` at `2441969` applies the declared 100 ms incoming / 200 ms outgoing durations to the zoom value. These declarations support per-button zoom animation; they do not establish a single highlight sliding between menu rows.

AMATV had also interpolated the white fill and reversed label through the outgoing 200 ms focus progress, leaving multiple pale capsules under key repeat. `CatalogSheetRow` now chooses its fill, foreground and shadow eligibility from the actual native focus state while retaining the 100/200 ms zoom and existing press animation. Only the current row receives Gray92; an old row keeps its shrinking geometry without a fading white fill. Its horizontal expansion is corrected from 8 dp to the declared 8 design pixels / 4 dp. Exact Luna vertical background expansion and shadow geometry are still renderer adaptations, not a pixel-equivalence claim.


The app's existing normalized canvas is 960×540; the shipped design canvas is 1920×1080. Thus 454→227 dp, 600→300 dp, 54→27 dp, 66→33 dp, 86→43 dp, 21→10.5 dp, and so on. The source panel offset [0,10] becomes 5 dp on this canvas.

## Implemented visual and input contract

`CatalogActionSheet` accepts `anchorBounds: Rect?` in screen pixels, `backdropLayer: GraphicsLayer?` for recorded underlying app content, and `lowPerformanceMode: Boolean`. Root freezes the source bounds at opening and pauses underlying focus requests. `LocalCatalogMenuAnchorReporter` reports focused/activated visual bounds through a non-state callback; menu rows override that callback with null.

- Main actions use the 227 dp / max 300 dp / radius 27 dp floating panel, 10.5 dp padding, 2 dp spacing, 33/43 dp measured rows, Caption1 12.5 sp / 16 sp leading, and native-style icon slots. The old large centered photo/title dialog is removed; the source card stays visible behind the Black30 scrim.
- Groups separate queue/native extras, library/favorite, ratings, playlist actions, artist/details and refresh. Separators, focus colors, hover candidate, leading selection checkmark and theme hairline follow the declarations above. Music semantics and generic vector icon artwork are AMATV adaptations.
- Contextual placement uses the frozen zoomed source bounds, 10 dp gap, 40/30 dp safe areas, fit-tested flips and final clamp. Without source bounds the safe-area-centered fallback is used.
- Opening/closing use the declared quadratic 380/210 ms motion and .35 scale. App-level callbacks that leave the sheet run **after the exit completes**. Inline panes use the declared 150+150 ms fade/25 dp translation; Back returns through the actual pane stack. Right release enters a submenu, Left release returns from a list pane, Enter activates, root Back dismisses, and first/last main action boundaries do not wrap.
- The Dialog supplies modal focus/IME handling with platform dim disabled. It does not draw default Android dialog chrome. Real playlist name/description fields use a 340 dp form pane as a deliberate Music workflow adaptation; text-field Left/Right caret behavior remains native.
- A provided recorded layer is sampled through the existing bounded-region `FrostedPanel` on API 31+; the menu tint is subdued dark Music material. Null capture, older API or low-performance mode uses the shipped RGB30/.9 fallback. Recording excludes the menu itself. The menu list alone gets an offscreen edge mask (11/10 dp scroll fades); no full-page bitmap or permanent blur is added.
- Low-performance mode or system-disabled animator playback uses immediate menu/focus state and bypasses blur. No continuous menu animation runs at rest. Font/accessibility tiers and native reduced-transparency controls are not asserted identical to Luna; the existing AMATV typography environment is retained.

## Functional behavior retained

### Deterministic entry and prompt Back handling

The later focus audit found that root entry used the first *enabled* action. While the state/permission read was pending, the leading Library/Favorite/Rating rows were disabled, so the opening focus jumped into Playlist/Create in the middle. A moving `firstActionFocus` alias also changed its row attachment as those reads completed. Root entry now targets the first actual row through that row's own requester, with a bounded four-frame layout handoff. Pending/already-completed actions retain keyboard focusability while their action remains disabled. Each pane visit places its entry once; late read results neither replay initial focus nor choose another row based on availability. A resumed window restores the last focused identity, and removing an action repairs to the nearest retained row.

The modal key gate separately evaluates navigation readiness and activation readiness. A disabled focused row permits Up/Down/Back, while Center still requires a fresh Down+Up owned by the ready Dialog and an available action throughout the press. A read that resolves between Down and Up cannot turn that held press into a write.

Back during queue preparation now cancels its read and performs the same Back intent immediately, instead of requiring a second press. Back also replaces an in-flight submenu transition; cancellation uses a generation guard so an older animation's `finally` cannot release a newer transition's input lock. Actual account writes retain their single-write busy protection. Opening remains 380 ms, closing 210 ms, and submenu fades 150+150 ms; no timing was shortened to hide the focus/read issue.

The first-use Back review also found that cold action/permission/artist/page reads remained live throughout the exit. Dismissal now invalidates and cancels those reads at input time, so their results/finalizers cannot change the exiting menu's rows or size. Queued pane/focus work cannot restart them while closing. The opening animation is explicitly cancelled (including a guard against a not-yet-dispatched opening effect), and the exit starts undispatched with the same 210 ms timing. These cancellation rules alone do not establish that every source of cold latency is resolved.

The action sheet uses inline panes for actions, paged playlist/artist choices, creation, and the verified Library management operations. Initial state/artist reads occur on opening rather than per card; pane reads cancel on disposal or replacement. Unsupported/unavailable actions are hidden. Read-only playlist targets are removed after the API reports they cannot be edited. Opening Playlists no longer erases already fetched artist relationships.

Library/favorite/rating/create/append mutations remain explicit click handlers with one bounded transport attempt and **no automatic write retry**. During a write, the result remains in the modal until the attempt returns. Errors do not claim success, and a later Refresh/read can confirm state. Verified capabilities include unfavorite, catalog-linked Library song removal, playlist rename/reorder/delete, and folder rename/move/empty-folder deletion. These use exact resource identity and readback; unknown results are resolved by GET, without repeating the write. Other operations remain hidden by capability. See [Library API](library-api.md).

## Validation boundary

Source/hash/locator declarations establish the inspected branches, not pixel-identical Luna material composition, exact native pressed interpolation, rolling truncation, accessibility tier metrics or cross-window rendering. AMATV retains its existing TV press feedback, ellipsis, compact edge fades and native form fields as named adaptations.

Per-candidate test counts and stopped-device instructions are archived under ignored `work/repo-review/20261104/archive/docs/apple-tv-context-menu.md`. Current functional capability and write-readback boundaries are recorded in [Library API](library-api.md) and [API capabilities](apple-music-capabilities.md).
