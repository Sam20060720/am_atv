# Interface styles

Settings offers **Classic** (the default) and **Apple style**. Classic uses the
presentation before `aa63879`, with `6754603` as its visual reference. Both styles
use the current account, catalog, playback, queue, lyrics, cache and management
implementations.

## Ownership

- `AmAtvApp` retains one playback runtime and supplies the selected
  `LocalInterfaceStyle`; switching does not recreate the Activity or player.
- `BrowseShell` owns one navigation flow and the complete Back press. It renders
  either `TvNavigation` or `ClassicNavigation`, never both.
- `BrowseTabSession` retains shared semantic browsers and detail visits. Home
  and Search view retention is separate for each style because card dimensions
  differ. Navigation focus targets are replaced when the style changes, and
  detached content focus intents are discarded.
- Screen content, input targets, actions and loaders remain shared. Style
  branches restore the classic top navigation, card geometry, collection layout,
  input decoration and player controls.

Changing the setting keeps the user in Settings and restores focus to the chosen
option. The handoff has a bounded lifetime and ownership token so a cancelled
request cannot overwrite a later choice. Missing or unknown stored values select
Classic; a valid saved choice, including Apple, is retained.

Classic navigation and virtual player controls register with the shared focus
overlay. Controls in native Dialog windows use the same painter locally because
the Activity overlay cannot draw above another window. A local fallback is not
drawn over an enabled global frame. Selection fills remain separate from remote
focus, and inactive controls release their frame when a menu takes input.
Catalog actions and lyric translation/pronunciation settings use the same
`TvMenuSurface`, `CatalogSheetRow` and `TvMenuFocusScope`. Classic menus have their
own window overlay outside panel/list clipping, larger rows and focus-safe
padding; Apple retains its existing action-menu metrics. Lyrics submenus restore
their source row, and closing restores the player's actual settings control.
Both menus use the same anchored placement and `TvMenuMotion` for scale/fade,
scrim, pane transitions and delayed dismissal. The player snapshots the lyrics
settings button's bounds just as it does for More; lyrics settings no longer
appear as an instant centered dialog. Native window animations/dimming are
disabled by the same setup, and Apple backdrop rendering uses the same animated
material bounds. Low-performance mode and system animation settings apply to
both menus through the shared motion owner.

Content-to-navigation handoff is one `BrowseShell` intent: cancel pending content
focus/scroll work before giving navigation ownership. Each style only places its
navigation targets. A later layout frame cannot reset a target already chosen by
the remote. Home initially enters the first available card, and explicit returns
retain their actual source. The Replay heading action is temporarily hidden in
both styles; its data and browser remain available in code. Classic card rows
stop at their left edge and use Up to reach the top navigation. Apple retains
Left-to-sidebar handoff. Both use the shared ownership/return machinery when a
navigation handoff is requested.

Input roots let text editing/IME handle Back first. Only an unconsumed fresh Down
grants the root ownership of its matching Up; an orphan release never pops the
page. Grid restoration uses the actual layout column count and collection paging
uses song positions after accounting for each layout's metadata headers.

Radio sources still load in parallel, but publish one ordered result with their
page cursors. Refresh keeps existing content until that result is ready. An empty
first visit waits for the parallel sources to settle. Cards keep their existing
opaque artwork crossfade without a second whole-card transparency animation.

Classic includes the current Radio, artist, full-section, Library, pagination,
context-menu and queue operations even when the original interface had no such
page. New surfaces use classic geometry while retaining their current behavior.

## Focused verification

The opt-in emulator regression `InterfaceStyleSwitchTest` exercises both style
switches, selected-option focus, a stable process/media session, and Back from
Radio/Home through exit confirmation. It restores the initial style and does not
start tracks or change account/queue data. Test results and build artifacts are
kept in ignored `work/` and `dist/` directories.

The collection row-placement unit test covers both the Apple list's two header
items and the Classic list's separate metadata column.

`InterfaceStyleSwitchTest#classicRemoteFocusPresentation` is a separate opt-in
focus check (`allow_interface_focus=1`). Its screenshots require visual review;
semantics alone do not prove a frame was rendered. It covers existing player UI
without starting playback, and records unavailable surfaces as skipped.

`homeHorizontalBoundaryHonorsInterfaceStyle` (`allow_interface_navigation=1`)
checks natural Home entry, each style's repeated/held Left behavior, Up-to-topbar
for Classic, navigation stability and exact source return.
`classicMenusKeepFocusWithinSharedSurface` (`allow_classic_menus=1`) checks real
long-press/lyrics menus, first/last-row spacing and source restoration without
activating cloud writes or changing lyric preferences. `radioFeedRetainsVisibleContent`
(`allow_radio_stability=1`) samples an ordinary Radio visit for six seconds. It
records whether source changes were actually observed and never treats warm
cached content as proof of an uncached load.
