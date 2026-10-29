# 070 — Favorites: pinned tools in the Device view

Date: 2026-10-29. Source: prototype `design/designs/ADB Toolbox Plugin.dc.html` (handoff 7) — markup
around `sec.favs` (lines ~310–390), `FAV_DEFAULT` (~1161), `localStorage` key `adbtb.favs` (~1273) and
the Favorites block of the render (`...(() => { const favs = …`, ~1965–2070). There is no written
spec; this file is it. `design/DEEP_LINK_DIALOG.md` already counts on it ("registered as a Favorites
item"; launches outside the dialog keep a toast).

## Goal

One section in the Device view with the tools a developer uses every day — toggles, actions,
presets, deep links and their own `adb shell` lines — each one click away, the same set in every
project. Today these are spread over Current app, Screen, Device, Display, Quick toggles, Language,
Location and Network, several of them below the fold.

## Dependencies

- 062/064 (Current app, Language & region, Location), 059 (quick toggles, rotation), 067, the Open
  deep link dialog (`design/DEEP_LINK_DIALOG.md`, `intellij/.../deeplinks/`).
- ADR 0004 (MVI, lifecycle), ADR 0005 (centralized ADB), ADR 0006 (persistence: a user-wide field
  "gets its own application-level `PersistentStateComponent`").

## Scope

**Phase 1** — section, tiles, edit mode, Add panel, catalog of the existing actions/toggles/presets
and of recent deep links, application-level persistence.
**Phase 2** — custom `adb shell` commands (box in the Add panel, tile, runner).

Each phase is one reviewable commit (`tasks/README.md` rule), Phase 2 after Phase 1.

## Out of scope

- New device behaviour: every tile runs an action that already exists in production (catalog table
  below). Items without a production counterpart are not added (see "Prototype-only items").
- Catalog items the prototype does not list, even if production has them: Full page screenshot,
  Accessibility audit, Network throttling, Reset all overrides, Logcat/Apps actions, App details
  deep links, MCP. (All Quick toggles tiles, incl. Developer and Connectivity, *are* in scope —
  the prototype's catalog uses its whole `displayToggles` list.)
- Exposing favorites to MCP agents, `AnAction`s per favorite, shortcuts per favorite.
- Import/export or sync of favorites beyond the IDE's own settings sync of an app-level component.
- Changing the existing sections (they stay where they are, with their own controls).
- Seeding demo content: `FAV_DEFAULT` is prototype demo data (see Persistence).

---

## Visual and behaviour spec (extracted from the prototype)

All colours are `design/tokens/tokens.json` tokens through `DeviceCockpitTheme`
(`intellij/.../ui/common/Theme.kt`); type sizes are the relative intent of `design/README.md` →
Fidelity (UI font, mono = editor font). `UI` = label font, `MONO` = mono font.

### Position and shell

- Device view, **second section**: directly after Current app (§3a), before Screen
  (`DeviceFactsPanel` stacks `currentAppSlot`, `screenSection()`, `deviceSection()`, `displaySlot`;
  Favorites gets a new `favoritesSlot` after `currentAppSlot`). Same section shell as every other
  section (padding 10/0/12, 1px bottom `border`).
- Only while the Device view shows content: with no device the whole view is the empty state, while
  loading the skeleton (prototype `showDeviceContent: connected && !s.skeleton`).
- **Collapsible** like every Device section: chevron + title fold it (tooltip "Collapse section" /
  "Expand section"); key **`favs`** (the prototype's key) in `adbToolbox.collapsedSections`
  (`CollapsedSectionsStore.KEY`); add `favs` to the key list in `design/README.md` §3. Meta and the
  Edit link stay visible and don't toggle.

### Header

- Title **"Favorites"** (section title style, 12.5/700).
- Meta, right-aligned (`margin-left: auto`): **"N pinned"** — `MONO` 9.5, `textFaint`; empty text when
  nothing is pinned. N = tiles actually shown (unknown/unresolvable entries are not counted).
- Link (11/600 `accent`, the section link style) **"Edit"**, **"Done"** while editing; hidden while
  nothing is pinned. Clicking it toggles edit mode **and closes the Add panel**.

### Empty state (nothing pinned)

Box: margin 0 10, padding 12, radius 6, 1px **dashed** `borderStrong`, column, gap 4, left-aligned,
11 `textDim`:

- **"Pin the tools you use every day"** — 12/600 `text`.
- **"Toggles, actions, presets and deep links — one click from here."**
- Secondary button (26 high, padding 0 10, radius 5, 11.5, 1px `borderStrong`, `text`), margin-top 6:
  **"+ Add favorites"** → opens the Add panel (query cleared).

### Tile grid

- Grid `repeat(auto-fill, minmax(130px, 1fr))`, gap 5, padding 0 10 — the `QuickToggleGrid`
  algorithm (`intellij/.../display/QuickToggleTiles.kt`) with a 130px minimum; rows as tall as the
  tallest tile.
- **Tile** (row, centered, gap 7, min-height 38, padding 4/8, radius 6):
  - off: background `field`, 1px `border`; hover fill `hover`.
  - **on** (`item.on`): background `accentBg`, 1px `accentBorder`; sub text `accent`; kind box
    transparent with `accentBorder` and `accent` glyph.
  - **Kind box** 18×18, radius 4, background `header`, 1px `border`, glyph `textDim`, 10/700 `MONO`;
    for presets 8.5/700 `UI`. Glyphs: toggle **◐**, action **▸**, preset **Aa**, link **↗**, custom
    command **$**.
  - Text column (gap 1, min-width 0, fills): label 11.5/600 `text`, one line, ellipsis; sub `MONO`
    9.5 `textDim` (on: `accent`), one line, ellipsis.
  - Tooltip: the item tip (column "Tip" below); "Drag to reorder" while editing.
  - Click runs the item (not while editing).
- After the tiles, an **"+ Add" tile**: min-height 38, radius 6, 1px dashed `borderStrong`, centered
  "+ Add" 11.5/600 `textDim`, hover fill, tooltip **"Pin more tools"**; opens the Add panel. Shown
  in and out of edit mode.

### Edit mode

- Tiles: border 1px **dashed** `borderStrong` (on tiles keep `accentBorder`), grab cursor, no hover
  fill, click does nothing, full opacity.
- Leading grip **"⋮⋮"** (9px, letter-spacing −2, `textFaint`) before the kind box.
- Trailing **×** (18×18, radius 4, 13px `textDim`, hover fill, tooltip **"Unpin"**) removes the
  tile at once (no confirmation, no toast).
- **Drag a tile onto another** to move it to that position (prototype: remove at `from`, insert at
  `to`); saved immediately.
- Hint under the grid (10.5 `textDim`, padding 2/10/0):
  **"Drag to reorder · × to unpin. Saved for your user, on every project."**

### Add to favorites panel

Inline under the grid / empty state (not a popup): margin 4/10/0, padding 8, radius 6, 1px
`borderStrong`, background `panel`, column gap 6 (prototype also draws `shadowPopup`; see open
decision 9).

- Header row: **"Add to favorites"** 11.5/700 `text` · spacer · link **"Done"** (closes the panel).
- Search field, full width, 26 high, `UI` font, placeholder **"Search tools…"** (Apps-list search
  primitive, `emptyText`). Case-insensitive substring match on label **or** sub. Opening the panel
  clears the query.
- Catalog list, max height 300, scrolls, rows gap 1. For each non-empty group: group label (9.5/700,
  letter-spacing 0.5, uppercase, `textDim`, padding 8/6/3), then rows.
- **Row** (gap 8, padding 4/6, radius 4, hover fill; pinned rows background `accentBg`): star 14px
  wide, 12px — **★** `amber` when pinned, **☆** `textFaint` when not · kind box (always the "off"
  style) · label 11.5 `text` (fills, ellipsis) · sub `MONO` 9.5 `textDim` (max 45% width, ellipsis).
  Tooltip = item tip. **Click toggles the pin**: pinning appends to the end, unpinning removes.
- No group matches → **"Nothing matches."** (hint style).
- Groups, in order (labels verbatim): **Current app, Device, Screen, Toggles, Font scale,
  Display size, Rotation, Background limit, Language, Mock location, Network, Recent deep links,
  Custom commands** (the last one is Phase 2).

### Custom command box (Phase 2)

At the bottom of the Add panel: 1px top `border`, padding-top 8, single column, gap 5.

- Group label **"New custom command"**.
- Name field, full width, 26 high, `UI`, placeholder **"Name, e.g. Clear notifications"**.
- Row (gap 5): prefix **"adb shell"** (`MONO` 10.5 `textDim`, no wrap) · command field (fills, 26
  high, `MONO` 11, placeholder **"cmd notification cancel-all"**) · primary button **"Pin"** (26 high,
  11, the view's accent primary).
- **Pin**: trims both; strips a pasted `adb [-s <serial>] shell ` prefix from the command
  (`TalkBackCommand.normalizeCustom` rule, see Architecture). Either empty → error (10.5 `red`)
  **"Name and command are both required."**; the error clears as soon as either field is edited.
  Success: appends the command to the favorites, clears both fields, toast **"Pinned “<name>”"**
  (curly quotes).
- Tile: kind **$**, label = name, sub = the command line, tooltip **"adb -s <serial> shell <command>"**.
  Click runs it; success toast **"Ran: <command>"**.
- The "Custom commands" catalog group lists only pinned custom commands, so unpinning one there (or
  × in edit mode) deletes it.

---

## Catalog mapping

Every id is the persisted key (see Persistence); the prototype's id is kept where it is stable,
toggles use production enum names instead of the prototype's label (`tg:Dark theme`). "Sub",
"Tip" and labels are verbatim from the prototype. All runs go through the existing ViewModel/
coordinator entry point, so the existing toasts, confirmations and readbacks apply unchanged.

### Current app (kind action, ▸; target = the Current app package)

Target package = `CurrentAppViewModel.state.display`: `App.app.packageName` (also when `killed`),
`Home.lastApp`; none otherwise (prototype `pkgNow`, which is the killed app's package too).

| id | Label | Sub | Tip | Production |
|---|---|---|---|---|
| `app:restart` | Restart app | current app | Force-stop + launch the foreground app | `CurrentAppViewModel.restartOrLaunchForeground()` (⌥⇧⌘R semantics: Launch when killed) |
| `app:kill` | Force stop | current app | am force-stop — data stays | `perform(CurrentAppAction.Kill, pkg)` |
| `app:perms` | Reset permissions | current app | pm reset-permissions | `perform(CurrentAppAction.ResetPermissions, pkg)` (all granted, task 067) |
| `app:death` | Process death | current app | Background + am kill — test state restore | `perform(CurrentAppAction.ProcessDeath, pkg)` |
| `app:clear` | Clear data | current app · confirm | pm clear — asks for confirmation | the tool window's `confirmCurrentApp(CurrentAppAction.ClearData, pkg)` then `perform(ClearData, pkg)` — same path as Current app's button |
| `app:uninstall` | Uninstall | current app · confirm | Asks for confirmation | same, `CurrentAppAction.Uninstall` |
| `app:details` | App tools & details | current app | Deep links, permissions, prefs, databases | `DeviceCockpitProjectService.openCurrentAppDetails(pkg)` (Apps › details, back link "← Device") |

### Device (action)

| id | Label | Sub | Tip | Production |
|---|---|---|---|---|
| `dev:reboot` | Reboot | device | adb reboot | `DeviceActionsViewModel.handle(DeviceActionsIntent.Reboot)` |
| `dev:wake` | Wake | device | input keyevent WAKEUP | `DeviceActionsIntent.Wake` |
| `dev:shell` | Open shell | terminal | adb shell in a new terminal tab | `DeviceActionsIntent.OpenShell` |
| `dev:report` | Copy device report | clipboard | Model, API, ABI, serial… | `DeviceFactsViewModel.handle(DeviceFactsIntent.CopyReport)` (toast from its effect) |
| `dev:deeplink` | Deep link | dialog | Open the deep link dialog | `DeviceCockpitProjectService.openDeviceDeepLink()` |

### Screen (action; Mirror and Record have an on state)

| id | Label | Sub | Tip | on | Production |
|---|---|---|---|---|---|
| `scr:mirror` | Mirror | "running" / "scrcpy" | Start / stop scrcpy | `MirroringViewState.presentationState is Running` | `MirroringViewModel.handle(MirroringIntent.Toggle)` — see open decision 5 |
| `scr:shot` | Screenshot | capture directory (prototype "~/Desktop", as in the Screen meta) | Save a PNG | — | `CaptureViewModel.handle(CaptureIntent.CaptureScreenshot)` |
| `scr:rec` | Record | "recording" / "screen" | Start / stop recording | `RecordingPresentationState.Recording` (also `Starting`) | `RecordingViewModel.handle(RecordingIntent.Toggle)` |
| `scr:inspect` | Layout Inspector | capture | Capture the view hierarchy | — | `LayoutInspectorService.open()` (project service, `intellij/.../inspector/LayoutInspectorEditor.kt`) |

Mirror is listed only while scrcpy is available; a pinned Mirror tile follows the Mirror button's
disabled rule (`ScrcpyAvailability.Missing`).

### Toggles (kind toggle, ◐)

Every Quick toggles tile that is not n/a on the current device (prototype filters `t.na`). Label,
sub (= the tile's current value text) and tip (= the tile's tooltip) are exactly what the Quick
toggles tile shows, so they come from the same presentation code (`QuickToggleTile` texts in
`DisplayPanel`), not re-typed. Click flips the toggle (`!on`); on = the tile's on state.

| id | Toggle | State → intent |
|---|---|---|
| `tg:DarkTheme` | Dark theme | `QuickTogglesViewModel` `darkTheme` → `QuickTogglesIntent.SetDarkTheme` |
| `tg:AnimationsOff` | Animations off | `animations` → `SetAnimationsOff` |
| `tg:ShowTouches` | Show touches | `showTouches` → `SetShowTouches` |
| `tg:TalkBack` | TalkBack | `talkBack` (+ `talkBackProfile` in the value) → `SetTalkBack` |
| `tg:BoldText`, `tg:InvertColors` | Bold text, Invert colors | `DeviceSettingTogglesViewModel` `toggles[DeviceSettingToggle.*]` → `DeviceSettingTogglesIntent.SetToggle` |
| `tg:StayAwake`, `tg:DontKeepActivities`, `tg:ShowViewUpdates`, `tg:ShowSurfaceUpdates` | Developer group | `DeveloperOptionsViewModel` `toggles[DeveloperToggle.*]` → `DeveloperOptionsIntent.SetToggle` |
| `tg:ShowLayoutBounds`, `tg:GpuOverdraw`, `tg:GpuProfileBars`, `tg:PointerLocation` | Developer group | `DeviceSettingToggle.*` → `SetToggle` |
| `tg:AirplaneMode`, `tg:Wifi`, `tg:MobileData` | Connectivity | `DeviceSettingToggle.*` → `SetToggle`; blocked over adb-over-Wi-Fi exactly like the Quick toggles tile (value "adb over Wi-Fi", reason tooltip, click does nothing) |

### Presets (kind preset, Aa; "on/off presets")

A preset is on when the device's read-back value equals it; clicking an on preset resets to the
default, clicking an off preset applies it. On presets show sub **"on · click to reset"**. Values
come from readbacks only (`QuickToggleFieldState.valueOrNull()` / `FontScaleState` / `DensityViewState`),
never from the click.

| Group | ids | Label | Sub (off) | Tip | on when | Apply / reset |
|---|---|---|---|---|---|---|
| Font scale | `font:<v>` for `FontScalePresets.VALUES` minus 1.0 (0.85, 1.15, 1.3, 1.5, 2) | "Font 1.3×" | font scale | "Toggle font scale 1.3× ↔ 1×" | `FontScaleState` current == v | `FontScaleIntent.Apply(v)` / `FontScaleIntent.Reset` |
| Display size | `dens:<p>` for `DensityPresets.PERCENTAGES` minus 100 (80, 90, 110, 125, 150) | "Display 110%" | display size | "Toggle display size 110% ↔ default" | `DensityReading.overrideDpi == percentToDpi(physicalDpi, p)` | `DensityIntent.ApplyPreset(p)` / `DensityIntent.Reset` |
| Rotation | `rot:Auto`, `rot:Portrait`, `rot:Landscape` | "Auto-rotate", "Lock portrait", "Lock landscape" | rotation | "Rotation: Portrait" | rotation == v and v ≠ Auto | `DeviceSettingTogglesIntent.SetRotation(v)` / `SetRotation(Auto)` |
| Background limit | `bg:0` … `bg:4` | "Bg limit 2" | background processes | "Toggle background process limit 2 ↔ standard" | `processLimit` == v | `DeveloperOptionsIntent.SetProcessLimit(v)` / `SetProcessLimit(BackgroundProcessLimit.STANDARD)` |
| Language | `lang:<tag>`: `LocaleViewState.TEST_LOCALES`, then `LocaleCatalog.entries`, without duplicates and without the original locale | the tag, e.g. "ar-XB" | TEST_LOCALES: explanation before " — " ("Pseudo RTL"); others the catalog title | "Toggle language ar-XB ↔ en-US" with the device's original instead of en-US | `LocaleViewState.locale.current` == tag | `LocaleIntent.Apply(tag)` / `LocaleIntent.Reset` (to the original, not en-US — decision 6) |
| Mock location | `loc:<city>` for `EmulatorLocationCommand.cities` | "Warsaw" | "52.23, 21.01" (2 decimals) | "Toggle mock location Warsaw ↔ off" | `LocationViewState.current?.first == city` | `LocationIntent.Preset(city)`; **no "off" exists** — decision 7 |
| Network | `net:proxy` | "Proxy <host>:<port>" | global http proxy | "Toggle global proxy" | `ProxyViewState.readState is ProxyReadState.Active` | `ProxyIntent.Enable(host, port)` / `ProxyIntent.Reset` — which endpoint: decision 8 |

Production toasts stay what each section posts today (e.g. rotation "Rotation locked to landscape",
language "Language set to ar-XB · activity restarted"); where the prototype flashes a toast that
production does not (font scale, density, toggles), no new toast is added (rule below).

### Recent deep links (kind link, ↗)

- Source: `DeviceDeepLinkUseCase.history()` (`DeviceDeepLinkHistory`, app-level, newest first, max 50);
  the catalog shows the **first 12**.
- id `dl:<uri>|<packageName>` (empty package = "Any app"). Label = the URI without its `scheme://`
  (or the whole URI); sub = app label (Apps rows / `PackageRepository`), else the package, else
  "Any app"; tip = `<uri>` or `<uri> → <package>`.
- A pinned link stays pinned when it falls out of the history (prototype rebuilds it from the id).
- Run: `DeviceDeepLinkUseCase.open(serial, uri, packageName)` **outside the dialog**, so it keeps the
  toast (`design/DEEP_LINK_DIALOG.md` §5): "Opened <uri> in <App label>", "Chooser opened for <uri>"
  (`AmStartOutput.chooserShown`), error toast "No app on this device handles <uri>"
  (`DeviceLinkResult.Failed.unresolved`); other `Failed` → error toast with its message. The use
  case is private (`deviceDeepLinks` lazy) in `DeviceCockpitProjectService`; expose a launch entry
  point rather than a second instance.

### Prototype-only items (excluded)

- **"Mock location ↔ off"** — production cannot clear an emulator GPS fix; the presets are pinned
  as apply-only unless decision 7 says otherwise.
- Nothing else: every other prototype catalog item has a production counterpart (verified above).

---

## Persistence

- **User-wide, every project** ("Saved for your user, on every project."). Per ADR 0006 a
  user-wide field gets **its own application-level component**, not a slice of
  `DeviceCockpitProjectState`. Model it on `intellij/.../persistence/DeviceDeepLinkHistory.kt`:
  `@Service(Service.Level.APP)`, `@State(name = "AdbToolboxFavorites", storages =
  [Storage("adbToolboxFavorites.xml")])` (the "AdbToolbox" prefix every existing state uses),
  `PersistentStateComponent<FavoritesState.Saved>` with a list of entries
  `{ id: String, name: String, command: String }` (`name`/`command` only for custom commands; deep
  links keep uri and package in the id, or as fields — implementer's choice, tested round trip).
- It implements an application port `FavoritesStore` (`load(): List<FavoriteRef>`,
  `save(List<FavoriteRef>)`, and a `changes: StateFlow`) so a change made in one open project shows
  in the others at once; each project's `FavoritesViewModel` collects it.
- **Default content: empty** (user direction, 2026-10-29). `FAV_DEFAULT` is prototype demo data
  and is not ported; a first-time user sees the empty state.
- Order is the list order. Unknown ids (an item a later version removed or a toggle that does not
  exist on this build) are **kept** in storage and not shown (the prototype hides them; it also
  drops them on reorder — production must not).
- Custom-command names may contain `|` (the prototype replaced it with `/` only because of its
  string ids; structured storage needs no such rule).

## Architecture

- **`application/favorites/`** (KMP-ready, no IntelliJ/Swing):
  - `FavoriteItem` (id, kind `Toggle|Action|Preset|Link|Command`, label, sub, tip, on, enabled +
    reason) and `FavoriteRef` (persisted).
  - `FavoriteSource` port: `items: Flow<List<FavoriteItem>>` + `run(id)`. One small source per
    feature area, each next to — and delegating to — the feature's existing ViewModel:
    `CurrentAppFavorites`, `DeviceFavorites` (device actions + facts), `ScreenFavorites`
    (mirroring/capture/recording + an inspector callback), `ToggleFavorites` (the three toggle
    ViewModels), `DisplayPresetFavorites` (font scale, density, rotation, background limit),
    `LocaleFavorites` (language + location), `ProxyFavorites`, `DeepLinkFavorites`,
    `CustomCommandFavorites` (Phase 2). Callbacks that only exist in the adapter (open the deep-link
    dialog, App details, Layout Inspector, Current app's confirmation) are injected as
    `() -> Unit` / `suspend (…) -> Boolean` lambdas, like `openCurrentAppDetails` today. **No giant
    favorites manager** reaching into every ViewModel; the toggle tile texts are shared with the
    Quick toggles code, not duplicated.
  - `FavoritesViewModel`: combines the sources' catalogs with the pinned refs; state = header meta,
    tiles, edit mode, Add panel open/query/filtered groups, custom-command fields + error; intents
    `Run, TogglePin, Move(from, to), Unpin, SetEditing, OpenAdd, CloseAdd, Search, EditCommandName,
    EditCommandLine, PinCommand`. Runs on the injected dispatchers, never the EDT.
- **`intellij/favorites/`**: `FavoritesSection` (Swing, tile grid, drag-and-drop with
  `TransferHandler`/`DnD`, Add panel) and `FavoritesCoordinator`, mounted into the new
  `DeviceFactsPanel.favoritesSlot` from `DeviceCockpitToolWindowPanel`; `FavoritesState` (app
  service) in `intellij/persistence/`. Wiring of the sources in `DeviceCockpitProjectService`.
  Disposal with the tool-window scope, like `CurrentAppCoordinator`.
- **No raw adb in UI.** Every tile goes through an existing ViewModel; custom commands go through
  `AdbTransport` (the project's `ConcurrencyLimitedAdbTransport`) from the application layer.
- **Custom commands and shell safety (Phase 2)** — same approach as the TalkBack custom commands in
  `domain/.../display/TalkBackCommand.kt`: the line is the user's own, run **unchanged as one
  `ShellToken.Literal`** (`AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal(line)))`,
  exactly like `TalkBackCommand.writeRequest`), with `&&`/pipes allowed, because the user is the
  author and the trust boundary. No runtime data (serial, package) is ever interpolated into it;
  the serial travels in `AdbDeviceRequest.serial`. Normalisation reuses
  `TalkBackCommand.normalizeCustom` semantics (leading `adb [-s x] shell ` and chained
  `&& adb shell ` stripped): move that function to a neutral domain helper
  (e.g. `domain/adb/UserShellLine.normalize`) with `TalkBackCommand.normalizeCustom` delegating to
  it — behaviour-preserving, existing `TalkBackCommandTest` stays green. A bounded timeout
  (decision 10) and cancellation through the coroutine scope; the result decides the toast.
  ddmlib reports no exit code (see `TalkBackCommand.parseServices`), so "failed" = transport
  failure, a real non-zero exit code or stderr — decision 11.

### Feedback rules for tiles

- A tile produces **exactly the feedback its home control produces**: it dispatches the same
  intent, and the existing coordinators (DisplayCoordinator, LocaleViewModel/LocationViewModel
  callbacks, CurrentApp events, Capture/Recording/Mirroring, DeviceFacts effects) post the toasts.
  No extra toasts for actions that have none today.
- New toasts only where this spec defines them: deep-link launches (§ Recent deep links), "Pinned
  “<name>”", "Ran: <command>", and the custom-command error (decision 11).
- Pinning/unpinning/reordering: no toast (prototype).
- Destructive tiles (Clear data, Uninstall) confirm with the existing Current app confirmation,
  never silently.
- While a tile's target is busy (e.g. `CurrentAppViewState.busy`, `QuickToggleFieldState.Applying`)
  the click is ignored like on the home control.

---

## Phase 1 — section, tiles, edit, catalog, deep links

### TDD plan

- `FavoritesViewModel`: empty by default → empty state, no meta, no Edit; pin appends, unpin
  removes; "N pinned" counts resolvable items only; unknown ids kept on save after reorder/unpin;
  `Move(from, to)` order; `SetEditing` closes the Add panel; Run ignored while editing; search
  matches label or sub, case-insensitive, hides empty groups, "Nothing matches." when none; group
  order as specified; `OpenAdd` clears the query.
- Store: changes from another project's view model arrive through `changes`.
- Each source, with fakes of its ViewModel/use case: catalog items (ids, labels, subs, tips
  verbatim), on state from readbacks (font 1.3 on only when the readback is 1.3; density via
  `percentToDpi`; rotation Auto never on; bg limit; locale vs original; location city; proxy
  Active), run dispatches the right intent (apply when off, reset when on), n/a toggles absent,
  Wi-Fi-blocked toggles disabled with the reason, Mirror absent while scrcpy is missing.
- `CurrentAppFavorites`: target from `App` (also killed) and `Home.lastApp`; Clear data / Uninstall
  call the confirmation and do nothing when it is declined; behaviour without a target per
  decision 3.
- `DeepLinkFavorites`: first 12 history entries; label without scheme; sub label → package →
  "Any app"; pinned link survives leaving the history; toast texts for opened / chooser /
  unresolved / failed.
- `FavoritesState` round trip (`getState`/`loadState`), empty default.
- Swing (`intellij` tests): header link and meta, empty state, tile on/off rendering, edit mode
  grip/×/dashed border/hint, × unpins, drag reorder through the transfer handler, Add panel rows
  with ★/☆ and `accentBg`, search filtering; collapsed key `favs`.
- Visual goldens (`docs/ui-visual-verification.md` matrix): `device-favorites-dark-dock.png`
  (tiles incl. on states), `device-favorites-edit-add-light-narrow.png` (edit mode + Add panel at
  300px); add them to the matrix.

### Acceptance criteria

- A new user sees the empty state; "+ Add favorites" opens the panel; pinning tiles from every
  group works and the tiles run the same behaviour as their home controls.
- Order, pins and unpins survive an IDE restart and are the same in a second open project.
- Presets show on/off from the device's real values and reset when clicked while on.
- A pinned deep link opens on the device with the toast from `DEEP_LINK_DIALOG.md` §5.
- Clear data / Uninstall from a tile always ask first.

## Phase 2 — custom commands

### TDD plan

- Domain `UserShellLine.normalize` (moved from `TalkBackCommand.normalizeCustom`): leading
  `adb shell `, `adb -s emulator-5554 shell `, chained `&& adb shell `, blank → null; TalkBack tests
  unchanged.
- `FavoritesViewModel.PinCommand`: empty name or command → "Name and command are both required.";
  editing either field clears it; success appends, clears fields, emits 'Pinned “<name>”'; a pasted
  `adb -s x shell cmd notification cancel-all` is stored as `cmd notification cancel-all`.
- `CustomCommandFavorites`: request = one `Shell(Literal(line))` for the selected serial (assert on
  `FakeAdbTransport`); success → "Ran: <command>"; transport failure / timeout / non-zero exit →
  error toast (decision 11); tip "adb -s <serial> shell <command>"; group lists only pinned
  commands; unpinning deletes it.
- Swing: the command box, prefix, error line, Pin.

### Acceptance criteria

- `cmd notification cancel-all` pinned as "Clear notifications" clears the device's notifications
  from its tile, with "Ran: cmd notification cancel-all".
- A failing command (e.g. `false` or an unknown binary) gives an error toast.

## E2E (`docs/e2e-testing.md`)

New `FavoritesE2ETest` (non-destructive), via `e2e/scripts/run-e2e.sh` with `E2E_HOME` set:

- Phase 1: empty state on a clean profile; pin **Dark theme** and **Font 1.3×** from the Add panel;
  click the tiles and read back `cmd uimode night` / `settings get system font_scale` (expect on
  state, then reset to 1 by clicking again); reorder and unpin in edit mode; restart-free check that
  the pins are still there after closing/reopening the tool window; pin a recent deep link (seed
  it through the dialog first) and see the toast.
- Phase 2: pin `settings put global adb_e2e_marker 1` (or another harmless setting), run it, read
  it back with `settings get global adb_e2e_marker`, then remove the marker.
- Update the test inventory table in `docs/e2e-testing.md`.

## Validation

`./gradlew :domain:test :application:test :intellij:test architectureCheck`, then
`./gradlew clean build koverVerify` before each commit; goldens regenerated in a full
`:intellij:test` run after review. No local `verifyPlugin`.

## Definition of done

- Both phases committed separately; Scope respected, nothing from Out of scope.
- Every visible string, size and token above matches the prototype; `design/README.md` gets a short
  "3d. Device view — Favorites" pointing to this task, plus `favs` in the collapsed-section keys and
  `favorites` under "Per application" in the State model.
- `domain`/`application` free of IntelliJ/Swing (architecture check green); no raw adb outside the
  centralized transport; nothing on the EDT.
- ≥ 80% coverage on changed code with behaviour tests; E2E green; goldens approved.
- Open decisions below answered by the user (or recorded as decided) before implementation starts.

---

## Open decisions

1. **Position.** Prototype: second section, after Current app, above Screen. Production's Screen
   toolbar now also lives on the rail. Keep "after Current app"?
2. **No device.** The prototype dims tiles to 45% without a device, but its Device view never shows
   the section then (empty state replaces the view). Production does the same. Keep Favorites
   hidden with no device, or show them (dimmed, with the "Connect a device to use this" reason)
   above the empty state?
3. **Current-app actions without a target** (Home with no last app, lock screen, System UI, reading,
   error). Prototype: Clear/Uninstall/Details do nothing; Restart/Kill/Perms/Death still "run".
   Proposal: tile disabled (45%) with the reason Current app shows; and follow Current app's own
   enablement rules (Kill disabled when killed, nothing on System UI). Where should that predicate
   live — today it is in `CurrentAppSection` (UI)?
4. **Destructive confirmations.** Clear data / Uninstall confirm (design rule). Should **Reboot**,
   **Reset permissions** and **Process death** from a one-click tile also confirm? The design
   rule says no; one stray click on a tile is easier than on the home button.
5. **Mirror tile.** The Mirror button opens the start dialog with options while idle (user request
   2026-10-14). From a tile: same dialog, or start with the saved options at once?
6. **Language "off".** Prototype toggles back to en-US; production resets to the device's
   **original** locale (`LocaleIntent.Reset`). Proposed: the original. Which languages are listed
   — the prototype list (incl. de-CH, nl-NL, tr-TR) or production's `LocaleCatalog` (proposed)?
7. **Mock location "off".** No production way to clear an emulator GPS fix. Options: apply-only
   presets (on = current city, click while on does nothing), or drop the group. On physical
   devices: hide the group or show dimmed ("Emulator only — …")?
8. **Proxy preset endpoint.** Prototype "Proxy host:port" from the Network form. Production: the
   active endpoint when on; when off — the form's current fields, the most recent entry, or one
   tile per recent proxy?
9. **Add panel shadow.** The prototype draws `shadowPopup` on an inline panel; the design says only
   popups/dialogs/toasts have a shadow (the device picker precedent drops it in the tool window).
   Proposed: no shadow.
10. **Custom command timeout and output.** Not in the prototype. Proposed 15 s (like deep links),
    output not shown; or show the first stdout line in the toast? Allow multi-line / newline?
    (Proposed: single line, newlines rejected.)
11. **Custom command failure.** Prototype only shows "Ran: …". Proposed error toast
    "Couldn’t run <name>: <first stderr line or outcome>"; with ddmlib's missing exit codes, is
    stderr output alone a failure?
12. **Keyboard / accessibility.** Drag is mouse-only in the prototype; task 050 requires keyboard
    access. Proposal needed for reordering without a mouse (e.g. a context menu "Move left/right")
    and accessible names for tiles ("Dark theme, on, toggle").
13. **Truncation.** Favorites tiles ellipsise label and sub (prototype), while Quick toggles tiles
    never truncate (design §5). Keep ellipsis here and put the full label in the tooltip?
14. **Unpinning a custom command deletes it.** Acceptable, or keep unpinned commands in the
    "Custom commands" group?
15. **Settings sync / roaming.** App-level state roams with the IDE's Settings Sync by default;
    custom commands may carry device-specific lines. Roam or `RoamingType.DISABLED`?
