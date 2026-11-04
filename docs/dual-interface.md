# Interface styles

Settings offers **Apple style** (the default) and **Classic**. Classic uses the
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
request cannot overwrite a later choice. Unknown stored values select Apple.

Classic navigation and virtual player controls register with the shared focus
overlay. Controls in native Dialog windows use the same painter locally because
the Activity overlay cannot draw above another window. A local fallback is not
drawn over an enabled global frame. Selection fills remain separate from remote
focus, and inactive controls release their frame when a menu takes input.

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
