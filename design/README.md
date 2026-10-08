# Handoff: ADB Toolbox — Android Studio / IntelliJ plugin

## Overview

ADB Toolbox is a single IntelliJ Platform **tool window** (docked right, ~380px) that puts the
adb operations an Android developer repeats dozens of times a day one click away: pick a device,
mirror it with scrcpy, restart / clear / uninstall an app, change font scale and display density,
set a global HTTP proxy, capture screen, and read Logcat.

Everything is scoped to one globally selected device, shown in a pinned bar at the top of the
tool window. Four views live on a 34px vertical icon rail: **Device, Apps, Network, Logcat**,
plus Settings pinned to the rail bottom.

> **There is no Display view.** Font scale, display scale (density) and the quick toggles are
> sections of the Device view (user decision, 2026-09-25). Do not re-add a Display rail entry or a
> separate Display view; §5 below describes those sections as they appear inside the Device view.

## About the design files

The files in `designs/` are **design references written in HTML** (self-contained prototypes of look
and behaviour). They are **not production code to port**. The task is to **recreate them inside the
IntelliJ Platform plugin environment** — Kotlin + Swing/UI DSL v2 (`com.intellij.ui.dsl.builder`),
JBUI components, JBColor, AllIcons + custom SVG icons — following the platform's established patterns.

Open the prototypes in a browser:

- `designs/ADB Toolbox Plugin.dc.html` — **the implementation target.** Interactive. The pill bar at the
  top switches theme (dark/light), dock width (560 / 380 / 300), view, and device state
  (connected / no device / loading / unauthorized), plus mirroring, recording and skeleton toggles.
  The frame's bottom-right corner is draggable to test other sizes.
- `designs/ADB Toolbox Design System.dc.html` — tokens: type, spacing, colors (dark + light),
  Logcat severity colors, surfaces, buttons/inputs/presets, rail & toolbar metrics, tooltips, states.
- `designs/ADB Toolbox IA.dc.html` — information architecture, the reasoning behind it, click costs
  per workflow, keyboard model, degradation ladder.
- `designs/ADB Toolbox Icons.dc.html` — logo and icon specimens. Production SVGs are in `icons/`.

`designs/support.js` is only the runtime that makes those HTML files render; ignore it for implementation.

## Fidelity

**High fidelity.** Colors, type sizes, control heights, paddings, copy and states are final and are
listed exactly below and in `tokens/tokens.json`. Recreate faithfully — but map every color through
`JBColor` / platform theme keys rather than hardcoding hex, so the plugin follows any user theme
(the two palettes below are the reference values for New UI Dark and IntelliJ Light).
Fonts must come from `JBUI.Fonts.label()` / `JBFont.small()`; never bundle a webfont. The sizes
below are the *relative* intent: body = default label font, caption = small font, mono = editor font.

---

## Global layout

```
┌──────────────────────────────────────────────┐ 32px  title bar   (plugin name, gear, more, hide)
├──────────────────────────────────────────────┤ 30px  DEVICE BAR  (pinned, never scrolls)
│ 34px │                                       │
│ rail │        active view (scrolls)           │
│      │                                       │
├──────────────────────────────────────────────┤ 28px  STATUS BAR  (pinned)
└──────────────────────────────────────────────┘
```

- Tool window body background = `bg`; device bar, status bar and toolbars = `header`; rail and
  grouped panels = `panel`; text fields = `field` (sunken).
- Sections inside a view are separated by a 1px bottom border, **not** cards.
- Only popups, dialogs and toasts have a shadow.
- Toast is anchored bottom-left, above the status bar, 8px inset, max 3 stacked, auto-dismiss 4s
  (errors persist until dismissed).

### Responsive rules (measured on tool window width)

| width | behaviour |
|---|---|
| >= 470 (`wide`) | Logcat shows the tag column (104px, mono, dim) |
| 340–469 (`dock`, default 380) | Logcat shows level + timestamp + message; device bar shows serial |
| < 340 (`narrow`) | device bar drops the serial and shortens "3 online" to "3"; device facts grid 2 columns instead of 3; preset chip rows wrap |

---

## Screens / views

### 1. Device bar (global, all views)

**Purpose:** show and switch the device every other action targets.

Height 30px, background `header`, 1px bottom border, `padding: 0 4px 0 8px`, gap 6px.

States:

- **connected** — clickable 24px selector row (radius 5, hover fill): 7px green dot ·
  device name (11.5px / 600) · serial (mono 9.5px, `textFaint`) · connection chip
  ("USB" / "Wi-Fi", 9px/700, 1px border, radius 3, padding 1px 4px) · 5px caret.
  Right side: "3 online" (mono 9.5px `textFaint`) + 22px refresh icon button,
  tooltip "Refresh device list  ⌘⇧D".
- **loading** — 10px teal spinner + "Querying adb devices…" (11.5px `textDim`).
- **unauthorized** — 7px amber dot + "Pixel 8 Pro — unauthorized" (11.5px/600, amber) + "Retry" link,
  and below the bar an amber banner (background `amberBg`, 10.5px, padding 6px 10px):
  "Accept the “Allow USB debugging” prompt on the device, then retry."
- **no device** — 7px hollow dot (1.5px `textFaint` border) + "No device connected" (11.5px `textDim`)
  + refresh icon button.

**Device picker popup** — ⚠ **reproduced pixel-faithfully** (handoff 4). Reference:
`screenshots/device-picker.png`; golden `device-picker-dark-wide`.

- **Container:** anchored under the device bar (`top: 62, left: 8, right: 8`), `panel` background,
  1px `borderStrong`, radius 8, content clipped to the rounded shape. No max-height until > 8
  devices; then the list scrolls and header/footer stay fixed. (A tool-window overlay, so the
  CSS popup shadow is not drawn.)
- **Header:** "CONNECTED DEVICES" — 9.5px / 700, uppercase, `textFaint`, padding 7px 10px 4px.
- **Row** (28px, padding 0 10px, gap 7, centered; full-width `hover` fill under the pointer / ↑↓):
  state dot 7px (`green` online, `amber` unauthorized) · name 11.5px, bold for the current device,
  never truncated · serial mono 9.5px `textFaint`, remaining width, ellipsis · connection chip
  right-aligned — "USB" / "Wi-Fi" / "Emulator", 9px / 700, padding 1px 4px, radius 3, 1px `border`
  + `textDim`; unauthorized: chip border **and** text `amber`.
- **Selected row:** full-bleed `accentBg` (composed over `panel`), bold name.
- **Footer:** 1px top `border`, `header` background, padding 6px 10px: link "Pair device over
  Wi-Fi…" left; hint "↑↓ to select · ⏎ to apply" right (mono 9px `textFaint`).
- **Behaviour:** opens on click of the selector, or Space / ↓ when the selector has focus; ↑↓ move
  the highlight, ⏎ applies, Esc / click outside closes without change. Opens with the current
  device highlighted.

### 2. Rail (global)

34px wide, `panel` background, 1px right border, `padding: 4px 0 6px`, gap 2px, centered items.
Buttons 26px square, radius 6, 16px glyph. Active = `accentBg` fill + 1px `accentBorder` +
accent-colored glyph; inactive glyph `textDim`, hover fill only. Settings button pinned to the bottom
via a flex spacer.

Badges: 5px dot at `top: 2, right: 2`.
- **amber** on Device when font scale != 1 or density != 100%; on Network when the proxy is enabled.
- **red** on Logcat when errors are arriving.

Tooltips: "Device — mirroring, capture, facts, display, quick toggles", "Apps — restart, clear data,
uninstall", "Network — global proxy", "Logcat — severity, filters, search".

### 3. Device view

Sections, each `padding: 10px 0 12px` with a 1px bottom border; section header row
`padding: 0 10px`, title 12.5px/700, right-aligned meta in mono 9.5px `textFaint`.

**Collapsible** (handoff 4): every section header in the Device and Network views starts with a
7×8px chevron (`textDim`, pointing down while open); clicking the chevron or the title folds the
section to its header row (tooltip "Collapse section" / "Expand section"). Header meta and links
stay visible and don't toggle. The collapsed set is application-level (`PropertiesComponent`
`adbToolbox.collapsedSections`): the same in every project and for every device, restored after a
restart. Keys: `app, screen, device, display, locale, loc, toggles, proxy, recent, throttle`.

**Current app** (§3a) is the first section; then:

1. **Screen** — merges the former Mirroring and Capture sections (handoff 4). Meta
   "~/Desktop · scrcpy 4.1" (capture directory · resolved scrcpy).
   - One toolbar row (padding 0 10, gap 4) of 30×28 icon buttons (radius 5, 1px `border`, 16px
     glyph), tooltips carry the full description + shortcut:
     **Mirror** — split button; its 16px caret ("Mirroring options — bitrate, resolution, stay
     awake, show touches") opens the inline options panel; **Screenshot** — split button; its caret
     opens a menu with **Full page** (ADR 0013; user decision 2026-10-08); **Record**.
   - **Mirroring options** panel under the toolbar (margin 0 10, `header` fill, 1px `border`,
     radius 5, padding 8 0 10, gap 8): title + note ("saved for this project"; "applies on next
     start" while running — options are per project, not per device as the handoff suggests);
     **Max resolution** (Original / 1920 / 1280 / 1024, `--max-size`) and **Bitrate** (4 / 8
     default / 16 / 32 Mbps, `--video-bit-rate`) dropdowns side by side; checkboxes **Stay awake
     while mirroring**, **Show touches**, **Turn device screen off** (`--turn-screen-off`). Every
     change applies at once (no OK button); a persisted custom value joins its list in order.
   - Second row: labelled 28px button **[layoutInspector] Inspect layout** ("Capture screen + view
     hierarchy into an editor tab  ⌥⇧⌘I"); "Re-capture layout" in the accent tone while the tab is open.
   - Active states are on the buttons: Mirror running → `brandBg` + `brandBorder`, teal glyph
     (click stops); Record running → `redBg` + `redBorder`, red stop glyph (click = stop & save).
     Status rows only while active: teal "Mirroring · 1080×2400 @ 60 fps" + **Stop**; red mono
     "Recording · 00:42" + **Stop & save** (toast "Saved screen-….mp4" + **Reveal**).
   - scrcpy missing (user decision, 2026-09-29): resolved up front; Mirror and its caret are
     disabled, the tooltip and a help line under the toolbar say why and how to fix it, e.g.
     "scrcpy is not installed, or not on PATH. Install it (brew install scrcpy) or set its path in
     Settings." Below it, link buttons **Open Settings · Check again · Install guide**.
   - Inspector open: note row "Inspector open · captured 12:04:31" + **Show** + **Re-capture**.
2. **Device** — header link "Copy report".2. **Device** — header link "Copy report". Facts grid (3 cols, 2 when narrow, gap 8, padding 2px 10px 6px):
   key 9.5px/700 uppercase letter-spacing .4 `textFaint`, value mono 11px.
   Android "15 · API 35" / Resolution "1080×2400" / Density "428 dpi" (or the overridden dpi) /
   Battery "72% · charging" / ABI "arm64-v8a" / Uptime "4h 12m".
   Action row: secondary **Reboot**, **Open shell**, **Wake**.

**Empty state (no device)** replaces the whole view: centered column, padding 34px 16px 16px, gap 6:
26×34 rounded outline glyph, "No device connected" (12.5px/700),
"Connect over USB with USB debugging enabled, or pair wirelessly. Actions stay disabled until a device
is online." (11px `textDim`, max-width 250, centered), primary **Refresh** + secondary
**"Pair over Wi-Fi…"**, then mono 9.5px footer "adb 35.0.2 · /opt/homebrew/bin/adb".

**Loading state:** six pulsing skeleton bars (height 10, radius 3, widths 62/88/40/74/54/82%,
gap 8, padding 12, `adb-pulse` 1.4s ease-in-out infinite).

### 3a. Device view — Current app

**Purpose:** act on the app that is on the device screen right now, without searching for it in Apps.
First section of the Device view, above Mirroring. Same section shell as every other section.

**Header:** title "Current app" · right meta (mono 9.5px `textFaint`, clickable, cursor pointer,
tooltip "Refresh now — also checks every 3 s while this view is visible"):
"updated 2 s ago" / "updated just now" / "reading…" / "not running" / "adb error" (`red`).
While a change is held (see Freshness) the meta is replaced by a link (11px→10.5px/600 accent):
"Maps came to the front · Update" (narrow: "Maps in front · Update"),
tooltip "Held while the pointer is over this section. Applies when you move away, or click to update now."

**Identity row** (padding 0 10px, gap 8, centered):
- 28px launcher icon, radius 6 (ADR 0010). Fallback = the Apps-list tile scaled to 28px:
  debuggable `brandBg` + 1px `brandBorder`, else `header` + 1px `border`. Killed: 55% opacity.
- Label 12px/700 `text`, ellipsised · "debug" tag (Apps-list tag, tooltip
  "android:debuggable=true — debugger, run-as and Shared prefs work") or "system" tag
  (connection-chip style: 9px/700 `textDim`, 1px `border`, radius 3; tooltip "Preinstalled on the system image").
- Package below, mono 9.5px `textFaint`, ellipsised, full value in tooltip.

**Facts** — reuses the Device facts grid (3 cols, 2 when narrow, gap 8, padding 2px 10px 6px):

| key | value | tooltip |
|---|---|---|
| ACTIVITY (spans all columns) | top resumed activity, short form when inside the package: `.checkout.CheckoutActivity` | full `pkg/.Activity` |
| VERSION | `4.12.0-dev (41200)` = versionName (versionCode) | "versionName … · versionCode …" |
| PROCESS | `8155 · 3m 12s` = PID · running for | "PID · running for" |
| TARGET SDK | `36` | "targetSdkVersion 36 · minSdk 26" |

Killed: Activity "—", Process "not running" (`textFaint`).

**Help text** (helpText style), only when relevant:
- killed — "Force-stopped. Stays here until another app comes to the front."
- System UI — "System UI draws the status bar, shade and lock screen. Actions are off so the device stays usable."

**Action row** (actionRow, wraps): primary **Restart** (tooltip "Force-stop, then launch the main activity  ⌥⇧⌘R") ·
secondary **Kill** (tooltip "am force-stop — leaves data intact") · secondary **Reset permissions**
(tooltip "Revokes runtime permissions and clears “Don’t ask again” — the app is stopped, data stays") · flex spacer · link **Details**
(tooltip "Open App details for com.acme.shop — Info, Shared prefs, Databases").
Killed: primary becomes **Launch** (tooltip "Launch the main activity  ⌥⇧⌘R"), Kill is disabled (tooltip "Not running"),
Reset permissions stays enabled.

**Reset permissions** is reversible (the user re-grants), so it sits with Restart/Kill, not in the
destructive group, and has no confirmation. Busy label "Resetting…"; result toast
"Permissions reset for com.acme.shop — 4 revoked" (or "— nothing to revoke"). Revoking stops the
process, so the section then shows the app as killed with Launch — the next launch sees first-run
permission prompts. Implementation: for each granted runtime permission from `dumpsys package <pkg>`
(`runtime permissions:` block) run `pm revoke <pkg> <perm>` and
`pm clear-permission-flags <pkg> <perm> user-set user-fixed`; skip `system-fixed` / `policy-fixed`
and count them out. Do **not** use `pm reset-permissions` — it resets every app on the device.

**Destructive group** — identical to Apps (dashed top border, "DESTRUCTIVE" label, red outlined
**Clear data** / **Uninstall**, same tooltips) and the **same two confirmation modals, copy unchanged**,
naming the foreground package captured when the button was pressed (a foreground change while the
modal is open never retargets it).

**States**

| state | identity | facts | actions |
|---|---|---|---|
| user app, debuggable | label + debug tag, teal tile | all 4 | all enabled |
| user app, release | label, neutral tile | all 4 | all enabled |
| system app (e.g. Settings) | label + system tag | all 4 | Restart, Kill, Reset permissions, Clear data enabled; **Uninstall disabled**, tooltip "Preinstalled system app — adb can’t uninstall it" |
| System UI (shade open) | "System UI" + system tag | Activity "NotificationShade (window)" | Restart, Kill, Reset permissions, Clear data, Uninstall **all disabled**, tooltip "Disabled for System UI — it draws the status bar, shade and lock screen"; Details enabled |
| killed / not running | dimmed tile | Activity "—", Process "not running" | **Launch** primary, Kill disabled, destructive enabled |
| home screen | 28px dashed tile (1.5px dashed `borderStrong`), "Home screen" + launcher package | — | note row: "Last app: Acme Shop" + **Launch** link (tooltip "Launch com.acme.shop  ⌥⇧⌘R"), or "No app in the foreground." when there is no last app (e.g. after Uninstall) |
| lock screen | dashed tile, "Lock screen" + `com.android.systemui` | — | note "Unlock the device to act on the foreground app." + **Wake** link (tooltip "input keyevent KEYCODE_WAKEUP") |
| reading (first query) | 28px skeleton tile + bars 46% / 70%, then bars 88% / 54% — the README skeleton bar, same pulse | — | — |
| adb error / unknown | "Couldn’t read the foreground app" (11.5px/600) + mono 9.5px reason, e.g. "dumpsys activity activities — timed out after 5 s" | — | note "The rest of this view still works." + **Retry** link |
| no device | existing whole-view empty state — nothing new | | |

Disabled = the global 45% rule; controls are never hidden while there is an app to act on.
Home / lock / reading / error collapse to the identity + note row because there is no target.

**In progress:** the pressed button shows a 9px white `adb-spin` spinner + "Restarting…" / "Launching…"
(primary, 85% opacity, cursor progress) or "Killing…" (Kill); every other Current app action is disabled
until it finishes. Result = the existing toast + status-bar message: "Restarted com.acme.shop",
"Launched com.acme.shop", "Force-stopped com.acme.shop", "Data cleared for com.acme.shop",
"Uninstalled com.acme.shop". Failures use the error toast, e.g.
"Couldn’t launch com.acme.shop — no launcher activity" with action **Details**.
After Restart, PID and running-for update (`8201 · 4s`); after Clear data the app shows as killed
(pm clear stops the process); after Uninstall the section shows Home screen with no last app.

**Details:** opens the existing App details page (Apps → detail, Info / Shared prefs / Databases),
Info tab, for the foreground package. The rail highlights Apps. The page’s back link reads
**"← Device"** (tooltip "Back to the Device view — scroll position is kept") and returns to the Device
view at the previous scroll position. Opened from the Apps list it keeps reading "← Apps".
The Apps list selection follows: `selectedPackage` becomes the foreground package (so Logcat’s
package filter and ⇧⌘R now target it). Restart / Kill / Clear / Uninstall from Current app do **not**
change the selection.

**Freshness**
- Query on: Device view shown, tool window shown, IDE frame regains focus, device switched,
  1 s after any of our own app actions (Restart/Launch/Kill/Clear/Uninstall, Apps actions too), header meta click.
- Poll every 3 s only while the Device view is visible and the IDE window is focused; stop otherwise.
- Cheap path first: compare only the foreground package + PID; re-read version / target SDK only when the package changes.
- No jumping: while the pointer is over the section, keyboard focus is inside it, an action is in progress,
  or a confirm is open, a detected change is **held** and announced in the header meta link.
  It applies on mouse-out after 600 ms, on click, or when focus leaves. Manual refresh never holds.
  The first query shows the skeleton; later refreshes keep the content and only change the meta to "reading…".

**Widths**
- wide 560 / dock 380 — facts 3 columns; Activity on its own row.
- dock 380 — Restart · Kill · Reset permissions · Details on one row.
- narrow 300 — facts 2 columns (Version · Process / Target SDK); pending link shortens to
  "Maps in front · Update"; label and package ellipsise first, tags never shrink. The action row wraps:
  Restart · Kill on the first line, Reset permissions · Details on the second (gap 6 both ways); labels are never
  abbreviated. The destructive group wraps its buttons under the label if the font is larger.

**Tokens / icons:** no new tokens. No new icons in the section (buttons are text). In the platform,
Restart/Kill map to the existing `restartApp` / `forceStop` action icons for the ⌥⇧⌘R action and
menus; the back link uses `AllIcons.Actions.Back`.

### 3b. Device view — Language & region

After Display scale. Header meta mono 10px: current locale "en-US" (`textFaint`) or "ar-XB applied" (amber).
- Search field (Apps-list search primitive, placeholder "Search languages and regions…", "×" clear) filters by
  code and name; max 6 results; empty → "No language matches “xx”. Search by name or code, e.g. pt-BR."
- Group label (9.5px/700 uppercase) "TEST LOCALES" (or "RESULTS" while searching), then 26px rows
  (Recent-proxy row primitive): mono 11px code (44px) + 10.5px `textFaint` one-line explanation, ellipsised, full in tooltip:
  - `en-XA` — "Pseudo accented — longer, accented text in [brackets] shows truncation"
  - `ar-XB` — "Pseudo RTL — mirrored layout with reversed English text"
  - `ar-EG` — "Arabic — real RTL strings and Arabic-Indic digits"
  - `he-IL` — "Hebrew — real RTL strings, Latin digits"
  Active row: `amberBg`, amber bold code, trailing 9px/700 amber "applied".
- Help: "Changes the system language; the foreground activity restarts. Pseudo-locales only change strings in builds with pseudoLocalesEnabled."
- When overridden: "Original is en-US" + **Reset to original** link. "Original" = the locale read when the device first connected (persisted per serial).
- It is an override: counts in the status-bar chip ("4 overrides · reset all"), amber Device rail badge, included in Reset all.
- Toasts: "Language set to ar-XB · activity restarted", "Language reset to en-US".

### 3c. Device view — Location

After Language & region. Header meta mono 9.5px: "Warsaw · 52.2297, 21.0122" / "37.4220, -122.0841" on emulators;
"emulator only" on physical devices.
- Chip row (preset chip primitive): Warsaw, London, New York, San Francisco, Tokyo, Sydney, Stockholm + dashed **"…"** (custom; accessible name "Custom location").
  Tooltip "52.2297, 21.0122 — adb emu geo fix 21.0122 52.2297" (note: longitude first). Selected chip uses the neutral
  selected style, not amber: an emulator has no "real" location to revert to, so it is not an override.
- Custom: 84px "latitude" field, mono ",", 92px "longitude" field, secondary **Apply**. Errors (10.5px red, field turns red):
  "Latitude must be −90 to 90", "Longitude must be −180 to 180". Enter applies.
- Help: "Sends a GPS fix with adb emu geo fix. It stays until you set another or cold-boot the emulator."
- Physical device: chips dimmed (45%), tooltip "Emulator only — a physical device reports its real GPS", help
  "Emulator only — a physical device reports its real GPS. Pick an emulator in the device bar."
- Toast "Location set to Warsaw (52.2297, 21.0122)".

### 4. Apps view

- Toolbar (padding 6px 8px, 1px bottom border): search field (24px, radius 4, `field` bg,
  1px `borderStrong`, 9px circle glyph, placeholder "Filter packages…", "×" clear when non-empty)
  + 22px icon button "Show system packages".
- List (flex 1, scroll, padding 4px 6px, gap 1): rows 34px, radius 5, gap 7:
  16px app tile — the app's own launcher icon when the device reports one (ADR 0010), otherwise
  radius 4; debuggable = `brandBg` + `brandBorder`, else `header` + `border` ·
  two-line text (label 11.5px, 700 when selected; package mono 9.5px `textFaint`, both ellipsised) ·
  "debug" tag (9px/700 teal on `brandBg`, 1px `brandBorder`, radius 3) for debuggable builds.
  Selected row: `accentBg` + 1px `accentBorder`. Filter matches package **and** label.
  Empty: "No packages match “<query>”" + "Search matches package name and app label." + "Clear filter" link.
- Action footer (pinned, `panel`, 1px top border, padding 8px 0 10px, gap 8):
  selected app label (12px/700) + package (mono 9.5px);
  action row: primary **Restart** (tooltip "Force-stop, then launch the main activity  ⇧⌘R"),
  secondary **Force-stop** ("am force-stop — leaves data intact"), secondary **Launch**
  ("monkey launch of the main activity");
  then a **Destructive** group separated by a 1px dashed top border (padding 8px 10px 0, margin 0 10px):
  uppercase 9.5px/700 label "DESTRUCTIVE" on the left, then red outlined 24px buttons
  **Clear data** and **Uninstall** (hover fill `redBg`).

- **Pins:** a pin glyph at the right edge of every row (`pinned` = accent filled, else outline
  `textFaint`). Pinned apps are listed first under a "PINNED" header (9.5px/700 `textFaint`),
  the rest under "ALL APPS"; headers appear only while something is pinned. Pins are per project,
  by package name, and are also the top section of Logcat's package picker.
- **App details:** clicking a row's app icon, or the footer's "Details" link, replaces the list
  with a detail page: header (← Apps link, Refresh, 28px icon, label, package · version), then
  tabs **Info** (key/value grid: version, SDKs, UID, debuggable, process, install/update times,
  installer, ABI, paths, file access, flags; permissions table), **Shared prefs** (file picker,
  Key/Type/Value table, Add / Remove / Revert / Save to device) and **Databases** (database and
  table pickers, paged editable rows, SQL field with results, Revert / Save to device). Saving
  force-stops the app first (ADR 0011).

Confirmations (the only two modals in the plugin) — 300px max, `panel`, radius 8, padding 14,
popup shadow; title 12.5px/700, body 11px `textDim`; actions right-aligned, gap 6:
**Cancel** is focused by default (1px accent border = focus ring) and the red filled destructive
button is never the default. Esc = cancel.

- Uninstall — "Uninstall com.acme.shop?" / "Removes the app and all of its data from Pixel 8 Pro.
  This cannot be undone." / OK label "Uninstall".
- Clear data — "Clear data for com.acme.shop?" / "Deletes databases, preferences and caches on
  Pixel 8 Pro, and signs the user out. This cannot be undone." / OK label "Clear data".

### 5. Display sections (inside the Device view)

Shown in the Device view, below its Device section, while a device is connected — not a view of
their own.

1. **Display** (handoff 4) — one section with **Font scale** and **Display scale** as two
   dropdowns side by side (grid `repeat(auto-fit, minmax(130px,1fr))`, gap 8; stacks below ~290px).
   Each column: 10.5px `textDim` label above a 24px combo. Font items: `0.85× 1× (default) 1.15×
   1.3× 1.5× 2× Custom…`. Display items: `80% · 336 dpi … 100% · 420 dpi (physical) … 150% · 630 dpi
   Custom…`. An applied custom value joins the list in order. Overridden combo: amber text + the
   platform's warning outline. "Custom…" opens the inline field row below (× / dpi + Apply);
   invalid shows the range in red. Header meta mono 10px: "default" or e.g. "1.15× · 504 dpi
   applied" (amber). Help text ("Percentages are relative to the physical density (428 dpi). Values
   outside 60–200% can make the UI unusable.") and the Reset rows ("Overriding device default (1×)"
   + **Reset**; "Physical density is 428 dpi" + **Reset to physical**) follow below the grid.
2. **Quick toggles** — tile redesign (2026-09-30, user-supplied prototype revision). Header meta
   mono 10px `textFaint` "N of 8 on" ("—" until loaded). Two groups, each a caption (9.5px/700
   uppercase `textFaint`) above a grid `repeat(auto-fill, minmax(150px, 1fr))`, gap 5, 10px inset:
   **Appearance & accessibility** — "Dark theme / night yes|no" (tooltip "cmd uimode night yes|no"),
   "Animations off / scale 1×|0×|mixed" (tooltip "Sets window, transition and animator scales to 0"),
   "Show touches / on|off" (tooltip "Useful while recording"),
   "TalkBack / on|off · Samsung|Google|custom" (the installed TalkBack is detected per device;
   Settings → "TalkBack on/off command" overrides the commands);
   **Developer** (user request, 2026-09-29) — "Stay awake", "Don't keep activities",
   "Show view updates", "Show surface updates" (needs `adb root` on most devices), value "on|off"
   ("n/a" with the reason as tooltip when the device refuses).
   Tile: padding 7/8/7/9, radius 6, label 11.5px + 22×12 switch (9px knob) on top, mono 9.5px value
   below (gap 3). On = `accentBg` + `accentBorder`, bold label, `accent` value; off = transparent,
   `border` outline, hover fill. Clicking anywhere on the tile flips it.
   Then **Background process limit** caption (same group style) with the value at the right
   (mono 10px: "standard" `textFaint` / "N max" amber) over a segmented control: `field` track,
   1px `borderStrong`, radius 6, padding 2, gap 2, 22px segments **Standard** (2 parts, UI font) ·
   **0 · 1 · 2 · 3 · 4** (1 part each, mono 11). Selected Standard = `header` fill + `borderStrong`;
   selected number = `amberBg` + amber outline/text; tooltips use Android's wording
   ("At most 2 processes").
   Errors from a read stay inline; an error ending the user's own change is also a toast.
   **Extended tile grid** (Device view → Quick toggles; tiles 2-up, 1-up below 340 px; label 11.5px / line-height 1.3, mono value 9.5px, 22×12 track):
   - **Labels and values are never truncated.** They wrap onto a second line (track stays top-aligned, margin-top 2px);
     tiles in a row stretch to the tallest one. No ellipsis anywhere in the tile grid.
   - *Appearance & accessibility*: Dark theme, Animations off, Show touches, TalkBack, **Bold text** ("weight +300" / "off";
     `settings put secure font_weight_adjustment 300|0`), **Invert colors** (`accessibility_display_inversion_enabled`).
   - *Developer*: Stay awake, Don't keep activities, Show view updates, Show surface updates, **Show layout bounds**
     (`setprop debug.layout` + SystemProperties poke), **GPU overdraw** ("show"; `debug.hwui.overdraw`),
     **GPU profile bars** ("bars"; `debug.hwui.profile visual_bars`), **Pointer location** (`system pointer_location`).
   - *Connectivity* (new group): **Airplane mode** (`cmd connectivity airplane-mode`), **Wi-Fi** (`svc wifi`; tooltip
     "… — turning off Wi-Fi can drop a wireless adb connection"), **Mobile data** (`svc data`).
   - **Rotation** — a segmented control (same primitive as Background process limit) under the groups:
     Auto · Portrait · Landscape; header value mono "auto-rotate" or "landscape · locked" (amber). Portrait/Landscape set
     `accelerometer_rotation 0` + `user_rotation 0|1`; Auto restores auto-rotate. Not counted in the override chip
     (same rule as Background process limit). Toast "Rotation locked to landscape" / "Auto-rotate on".
   - Unavailable tiles stay visible at 45%: value **"n/a"** and the reason as tooltip —
     Mobile data "No cellular radio on this device". Blocked tiles show value "adb over Wi-Fi": Airplane / Wi-Fi when the
     device is connected over Wi-Fi, tooltip "Connected over Wi-Fi — turning Wi-Fi off would drop this adb session. Connect over USB to use it."
     Bold text on API < 31: "n/a", "Needs Android 12 (API 31)".
   - Header meta "N of M on": M counts tiles that are not n/a (blocked tiles still count).

### 6. Network view

1. **Global HTTP proxy** — header meta 10px/700: "off" (`textFaint`) or "active" (amber).
   Field row (padding 0 10px, gap 5): host field (flex, mono 11px, placeholder "host or IP") ·
   mono ":" · 66px port field. Invalid port → red border + red text + "Port must be 1–65535" (10.5px red)
   and the primary action is disabled.
   Action row: **"Use my computer IP"** link (tooltip "Fills your machine's LAN address — 10.0.4.117 on en0")
   on the left, primary **"Enable proxy"** on the right (becomes a secondary **"Disable"** when active).
   Active banner (margin 2px 10px 0, padding 6px 8px, radius 5, `amberBg`, 1px amber): pulsing amber dot +
   "All traffic routed through 10.0.4.117:8888" (11px/600 amber) + **Reset** link.
   Help when active: "Survives reboot until reset. Some apps pin certificates and will still fail."
   Help when off: "Sets \`settings put global http_proxy\`. Recent targets are remembered per project."
2. **Recent** — 26px rows: mono 11px target + right-aligned 10.5px `textFaint` note.
   `10.0.4.117:8888 · mitmproxy`, `10.0.4.117:8080 · Charles`, `proxy.acme.dev:3128 · staging`.
   Clicking a row fills host+port without enabling.

**Network throttling** (emulator only, second section): chip row Off · LTE · HSPA · 3G · EDGE ·
GPRS (each sets `emu network speed` + `delay`); header meta shows the emulator's readback (amber
when throttled); on a physical device the chips are disabled with the explanation that Android has
no throttling command without root.

### 7. Logcat view

- **Toolbar** (padding 6px 6px 6px 8px, 1px bottom border): search field (placeholder "Search log…  ⌘F",
  clear "×"), then 22px icon buttons: **Pause** (toggled = `accentBg` + `accentBorder`; glyph switches
  between two bars and a play triangle; tooltip "Pause the stream  Space" / "Resume the stream  Space"),
  **Autoscroll** (toggled on by default; tooltip "Autoscroll on — following the newest line" /
  "Autoscroll paused — End to resume"), **Wrap lines**, 1px divider, **Clear**
  (tooltip "Clear the buffer — does not clear the device log").
- **Filter row** (padding 0 8px 6px, gap 3, 1px bottom border): five 20px square level chips
  `V D I W E` — "min level and above"; the active chip is `accentBg` + `accentBorder` and takes the
  level's own color; then a right-aligned package filter chip (mono 10px, max-width 160, ellipsis)
  showing the package chosen in Logcat's own picker + " ▾", or "all packages ▾"; clicking opens a
  searchable popup: "All packages", a "Pinned" section (the Apps view's pins), then every other app.
  The Apps selection never narrows Logcat (explicit user decision, 2026-09-25).
- **Body** (`bg`, padding 4px 0 6px, scroll): rows are `display:flex; gap:7; padding:0 8px`,
  all cells mono 11px / line-height 16px. Level letter 7px wide; timestamp `logDim`; tag 104px
  `textDim` ellipsised (wide only); message flex, ellipsis or `pre-wrap` when wrap is on.
  Assert rows get a `redBg` row tint and 700 weight. Search hits: amber highlight on the matched
  substring only.
- **Paused indicator** — floating pill centered at `bottom: 34` (above the footer): `panel` bg,
  1px amber, radius 999, shadow: amber dot + "Paused · 234 new lines" (or "234 new lines below"
  when only autoscroll is off) + accent **"Jump to latest"**. Shown whenever paused OR autoscroll is off.
- **Footer** 22px, `header`, mono 9.5px: "<n> of 1 482 lines" left, "buffer 16 MB" right.
- Empty states: no device → "No device connected" / "Logcat attaches automatically when a device comes
  online."; cleared → "Buffer cleared" / "New lines will appear as the device emits them.";
  filtered out → "Nothing matches these filters" / "Level is E and above, limited to com.acme.shop,
  matching “timeout”." + **"Reset filters"** link.

### 8. Status bar (global)

28px, `header`, 1px top border, padding 4px 8px, gap 8 (raised from 22px / 0 8px: the chips
touched the bar's edges):
- running-process chip (only while mirroring/recording): pulsing dot + mono 9px/700 label
  "scrcpy" (teal, `brandBg`) or "REC 00:42" (red, `redBg`);
- MCP chip (only while an agent is connected; §11): same chip, teal, mono 9px/700 "MCP · Claude Code" ("MCP" below 340 px);
  the dot is static while idle and pulses (0.9s) while a tool call runs. Tooltip "Claude Code connected over MCP · Full control —
  click for AI agent settings"; click opens Settings › Tools › ADB Toolbox scrolled to AI agents.
  The status message shows the last call: "Claude Code · tap(540, 1210) on Pixel 9";
- last command message, 9.5px `textDim`, ellipsised;
- right side: override chip "3 overrides · reset all" (9px/700 amber, 1px amber border, radius 3,
  clickable — reverts font scale, density and proxy on the current device;
  tooltip "Revert font scale, density and proxy on this device") and mono 9px "adb 35.0.2".

### 9. Layout Inspector (editor tab)

**Surface:** a file-less editor tab ("Layout · Pixel 9 · 12:04:31", inspector icon) — full IDE width, splittable,
one tab per capture (re-capture replaces in place). The tool window keeps working meanwhile; Capture shows the
"Inspector open" row. Open from: Capture → **Inspect layout**, Find Action "Inspect Layout", ⌥⇧⌘I (global).
Implement as `FileEditorProvider` over a `LightVirtualFile`.

**Toolbar** (34px, `header`): secondary **Re-capture** (busy: teal spinner + "Capturing…"; disabled with
"Connect a device to use this") · mono 10px meta "Pixel 9 · 12:04:31 · com.acme.shop/.checkout.CheckoutActivity · 1080×2424 · 420 dpi"
(ellipsised) · toggle chips (22px; on = `accentBg` + `accentBorder`): **8 dp grid** (G), **Color picker** (I),
**Overlay**, **Accessibility** (A, red count badge = stops with problems) · divider · zoom − / "Fit · 74%" / +.
Below 1000 px a **Tree** chip appears and the tree becomes optional.

**Layout:** tree 260 (220 below 1300; hidden below 1000 unless toggled) · canvas (flex, `bg` with 16px dot pattern) ·
right panel 300 / 280 / 260. Status bar 22px: cursor "x 212  y 148 dp" · hover / measurement message · "1 dp = 2.625 px · 420 dpi".

**Canvas:** frozen screenshot (radius 10, 1px `borderStrong` + soft shadow) with the hierarchy on top. No live mode.
- Hover: deepest node under the cursor, 1px dashed accent outline + `accentBg` fill, label above "Button · 100 × 48 dp".
- Click selects: 2px accent outline, label below "100 × 48 dp" (accent pill, mono 10px/700 white).
- Selected + hover another: redlines in the new `measure` color — 1px lines with 7px end caps and a pill label "12 dp".
  Disjoint → the horizontal and/or vertical gap; one inside the other → the four paddings; overlapping → left/top edge deltas.
  The status bar repeats them: "Button → EditText · 12 dp".
- Zoom: Fit (default), 50 / 75 / 100 / 150 / 200 %; 100 % = 1 dp per screen px. F fit, 1 = 100 %, + / − step.
- 8 dp grid: 1px lines at 8 dp, accent at 22 % alpha.
- Color picker: crosshair cursor, loupe next to the cursor (18px swatch + mono hex + "click to copy"); click copies,
  ⌘C copies the last color. Toast "Copied #6750A4". Sampled from the screenshot pixels (the prototype samples node fills).
- Text size: "≈ 16 sp (estimated from glyph height)" for text nodes = text bounds height ÷ line count ÷ 1.17 ÷ font scale.

**Hierarchy tree:** 22px rows, 12px indent per level, ▾/▸ caret, short class (11.5px) + mono 10px dim
"“text”" / "“content-desc”" / resource-id. Selected = `accentBg` + 2px accent left bar; canvas hover highlights the row
and vice versa. Search "Search text or resource-id…" matches text, content-desc, hint, resource-id, class;
matches in amber, ancestors kept; empty → "Nothing matches “…”. Search covers text, content-description, resource-id and class."
Footer "25 nodes · uiautomator".

**Attributes panel:** class short (12.5/700) + full class mono; key/value rows (key 9.5px uppercase, value mono 11px,
ellipsised, "Copy" link per row): resource-id, text, hint, content-description, class, package, bounds (dp)
"x 295  y 232  ·  100 × 48", bounds (px) "[774,609][1037,735]", text size, background (sampled).
**State** section: chips clickable, long-clickable, focusable, focused, enabled, checkable, checked, selected,
scrollable, password (true = `text` + bold + `borderStrong`, false = faint + strikethrough).
Help "With an element selected, hover another to measure the distance between them. ↑ parent · ↓ first child · ← → siblings."
Empty: "Click an element on the screenshot or in the tree to see its attributes."

**Design overlay:** Overlay chip opens a drop card on the canvas ("Design overlay" / "Drop a PNG exported from Figma here,
or choose a file. 1× = dp size; 2× and 3× exports are scaled down." / **Choose PNG…** / **Use sample** (prototype only) / Cancel).
Dropping a PNG on the canvas works any time. Loaded → a 34px overlay bar above the canvas: file name · scale segmented
**1× 2× 3× Fit width** (auto-picked from the PNG width: 411 / 822 / 1233 px) · Opacity slider 0–100 % (default 50) ·
blend **Normal / Difference** · nudge readout "x +2  y −1 dp" + Reset · **Clear**. Arrow keys nudge 1 dp, ⇧ + arrow 8 dp
(while an overlay is loaded, arrows nudge it; use the tree to navigate). Overlay is unavailable on a secure capture.

**States** (pills): captured · capturing (dashed device outline, spinner, "Capturing Pixel 9…", "Screenshot and uiautomator
hierarchy. Usually under 2 s; screens that never go idle take longer.") · device gone (capture stays usable; amber banner
"Pixel 9 disconnected — this capture stays readable. Re-capture needs an online device.") · no device (no capture yet:
"No device connected" + disabled Capture) · secure screen (FLAG_SECURE: black screenshot, hierarchy drawn as wireframes,
banner "com.acme.shop marks this screen secure, so the screenshot is black. The hierarchy is still inspectable as wireframes;
colors and the overlay need a screenshot.") · error ("Couldn’t capture the layout", mono red "uiautomator dump: ERROR: could not
get idle state.", "Usually an animation that never settles. Turn Animations off in Device → Quick toggles, then retry.",
**Retry** + **Open Quick toggles**).

**Keys (canvas focused, no modifiers):** G grid · I color picker · A audit · F fit · 1 100 % · + / − zoom ·
arrows navigate selection or nudge the overlay · Esc leaves the picker, then deselects · ⌘C copies hex (picker) or the
selected resource-id / text.

### 10. Accessibility audit (inspector mode)

Toggle **Accessibility** chip or A. Canvas: every screen-reader stop gets a 16px numbered badge at its top-left corner
(accent; red when it has problems) and problem nodes get a 1.5px dashed red outline. Order = estimated TalkBack linear
order (hierarchy order of focusable / text nodes, then position) — the panel says so.
Right panel replaces Attributes:
- Header "Accessibility" + meta "19 stops · 4 with problems".
- Three summary cards (red when > 0): **Unlabeled buttons**, **Images without description**, **Targets < 48 dp**.
- "Problems only" checkbox + **Copy report** (Markdown: title with activity, device/time/counts, bullet counts, table
  "# | TalkBack announces | Element | Problems"). Toast "Copied audit report (Markdown) · 19 stops, 4 with problems".
- List rows: number badge, announcement in quotes ("“Unlabeled, button”", "“Coupon code, edit box”",
  "“Save card for later, checkbox, checked”"), mono class · resource-id, red problem chips
  ("Unlabeled button", "No content description", "Target 36×36 dp < 48"). Row ↔ canvas hover and selection are synced;
  the tree shows a red dot on problem nodes.
- Rules: unlabeled button = clickable Button/ImageButton with no text and no content-desc; image = ImageView without
  content-desc; target = clickable or focusable with width or height < 48 dp.

### 11. Settings — Tools › ADB Toolbox

Standard `Configurable` page, groups with titled separators, labels 100px:
- **Paths** — adb, scrcpy (path field + browse; detected version in mono green).
- **Capture** — Save to; checkbox **"Also copy screenshots to the clipboard"** (default on), comment
  "Applies to every screenshot, including full-page captures. The toast says “copied to clipboard” when it happened."
- **Logcat** — buffer size (MB).
- **AI agents (MCP)** — comment "A local MCP server lets coding agents drive the device selected in the tool window.
  It only listens on 127.0.0.1 and needs the token below."
  - Access radios: **Off** ("No server runs. Default.") / **Read only** ("Agents can see the screen, UI tree, logcat,
    app list, prefs and databases. They can’t change anything on the device.") / **Full control** ("Agents can also tap,
    type, change device settings and manage apps. Uninstall and Clear data still ask here first.").
  - Status box (teal when running): "Listening on http://127.0.0.1:47123/mcp · 1 agent connected" /
    "Claude Code · last call get_ui_tree 4 s ago · Pixel 9" + **Copy URL**; off: "Server stopped" /
    "Choose Read only or Full control to start it. Nothing listens while it is off."
  - Port (picked on first start, then kept; 127.0.0.1 only) · Token (masked "atk_••••3f9a", **Copy**, **Regenerate** —
    tooltip "Disconnects every agent; they need the new token"). Dimmed while Off.
  - **Set up an agent** — tabs Claude Code / Codex CLI / Cursor / Gemini CLI / Other, mono snippet with its target
    file and **Copy** (copies with the real token). Claude Code:
    `claude mcp add --transport http adb-toolbox http://127.0.0.1:47123/mcp --header "Authorization: Bearer …"`.
    Comment "Copy inserts the real token. Check the agent’s docs if its config format has changed."
  - **Exposed tools** table grouped See / Act / Device / Apps / Data: mono name, description, red "asks first" tag on
    destructive tools, level Read / Full. In Read only, Full tools render at 45% with tooltip
    "Needs Full control — not exposed to agents in Read only" (they are not listed to the agent at all).
  - Policy comment: "Uninstall and Clear data always open the same confirmation as in the tool window, naming the agent —
    also in Full control. Cancel, or no answer in 60 s, returns “declined by user”. Shared prefs and databases are readable
    only for debuggable apps (run-as)."
- **Agent confirmation** (tool window): the existing Uninstall / Clear data modal, unchanged title/body, plus a teal 10.5px line
  "Requested by Claude Code over MCP. Cancel tells the agent you declined." The tool window is shown if hidden.
  Results: toast "Uninstalled com.acme.shop · requested by Claude Code" / "Declined Claude Code’s request to uninstall com.acme.shop".

---

## Interactions & behaviour

- **Disabled-by-context:** with no online device every device-mutating control renders at
  **45% opacity**, keeps its tooltip and gains the reason "Connect a device to use this".
  Navigation, settings and reading remain enabled. Never hide controls — dim them.
- **Feedback:** all reversible actions produce a toast (no dialogs). Success = green left border +
  "✓"; error = red left border + "✕", persists, and carries the single action that fixes it
  (e.g. "scrcpy not found on PATH" → **Set path…**). Every toast text is also written into the
  status-bar message.
- **Optimistic + confirm:** overrides apply immediately and are reversible via Reset; only uninstall
  and clear-data confirm first.
- **Selection sharing:** the device in the bar scopes every view. The Apps selection is *not*
  shared with Logcat — Logcat has its own package picker (see §7); Apps pins are shared with it. The device selection and the pins persist per project.
  Current app reads the foreground package independently; only its **Details** link moves the Apps
  selection (see §3a).
- **Foreground tracking:** see §3a Freshness. Detected changes never move content under the pointer
  or retarget an open confirmation.
- **Device disconnect:** remember applied overrides per serial and offer to re-apply when that serial
  returns.
- **Animations:** `adb-spin` 0.7s linear infinite (spinners); `adb-pulse` 1.2–1.6s ease-in-out infinite
  (live dots, skeletons); toggle knob `left` transition 0.15s. Nothing else animates.
- **Keyboard:** Tab/⇧Tab between regions (device bar → rail → view → status bar); arrows within a region;
  ⌘F focuses Logcat search, Esc clears then blurs; Space toggles Logcat pause; End resumes autoscroll and
  jumps to the newest line; Enter applies a custom value.
  Suggested global shortcuts: ⇧⌘M mirror, ⇧⌘R restart the app selected in Apps,
  ⌥⇧⌘R restart the foreground app (launches it when Current app shows it as killed),
  ⇧⌘L focus Logcat, ⌘⇧D refresh devices, ⌥⇧⌘I capture into the Layout Inspector. Kill, Reset permissions, Clear data
  and Uninstall have no shortcuts. Inspector single-key shortcuts (§9) only act while its canvas has focus.

## State model

Per project (persist with `PersistentStateComponent`): `selectedDeviceSerial`, `selectedPackage`,
`proxyHost`, `proxyPort`, `recentProxies`, `logMinLevel`, `logPackageFilterOn`, `logWrap`,
`captureDir`, `adbPath`, `scrcpyPath`, `lastView`.

Runtime (view models): `devices[]` + `deviceState = connected|loading|unauthorized|none`,
`pickerOpen`, `apps[]` + `appQuery`, `fontScale`, `density`, `darkMode`, `animations`, `showTouches`,
`proxyEnabled`, `mirroringProcess`, `recordingProcess` + elapsed, `logBuffer`, `logQuery`, `paused`,
`autoscroll`, `toasts[]`, `pendingConfirm` (now `{kind, pkg, from: apps|current}` — the package is captured
when the dialog opens), `foreground = loading|app|home|lock|error` + `foregroundApp`
(`pkg, label, versionName, versionCode, debuggable, system, activity, pid, startedAt, targetSdk`),
`lastForegroundPkg` (survives Kill; cleared by Uninstall or when another app comes to the front),
`foregroundKilled`, `pendingForeground` (held change), `currentAppBusy = restart|launch|kill|perms|null`,
`appDetailsOrigin = apps|device` (drives the back link label and target),
`locale` + `originalLocale` (per serial), `location {name, lat, lon}` (emulators), `rotation = auto|portrait|landscape`,
`boldText`, `invertColors`, `layoutBounds`, `gpuOverdraw`, `gpuProfileBars`, `pointerLocation`, `airplane`, `wifi`, `mobileData`,
`inspectorTabs[]` (`capture {png, tree, device, activity, time}`, `zoom`, `grid`, `picker`, `audit`, `problemsOnly`,
`overlay {png, scale, opacity, blend, nudge}`, `selectedNode`), `mcp {access = off|read|full, port, token, sessions[], lastCall}`,
`pendingConfirm.from` gains `agent`.
Per application (not per project): `copyScreenshotsToClipboard = true`, `mcpAccess = off`, `mcpPort`, `mcpToken`,
`collapsedSections: Set<String>` (§3).
Derived: `overrideCount = (fontScale != 1) + (density != 100) + proxyEnabled + (locale != originalLocale)` → status-bar chip and
the amber rail badges. Everything device-touching is disabled when `deviceState != connected`.

## Current app — decisions

- **Position: first section.** Restarting the app on screen is the most frequent loop; it sits above Mirroring so it is visible without scrolling at all dock heights.
- **Freshness: event-driven + 3 s poll while visible, held under the pointer.** Catches external changes quickly without polling a hidden view; holding prevents a different package landing under a click.
- **Kill: keep showing the killed app** as "not running" with Launch. Kill-then-cold-start is the reason to kill; switching to Home screen would lose the target.
- **Facts: Activity, Version, Process, Target SDK.** Activity answers "which screen am I on", version confirms the build that was deployed, PID/uptime confirm a restart actually happened, target SDK explains most behaviour differences. Min SDK, installer, paths live in Details.
- **Shortcuts: separate.** ⇧⌘R keeps meaning "the app selected in Apps"; ⌥⇧⌘R means "the app in front". Same verb, one modifier apart, never ambiguous.
- **Details moves the Apps selection** — opening details is an explicit choice of package; back returns to Device.

**To find out on the device**
- Foreground detection across API levels: `dumpsys activity activities` → `topResumedActivity` (API 29+) /
  `mResumedActivity` (older); fallback `dumpsys window` → `mCurrentFocus` / `mFocusedApp`. Output format is
  not an API; parse defensively and test on API 26, 30, 34, 35+ and OEM skins.
- Distinguish launcher (resolve `cmd package resolve-activity -c android.intent.category.HOME`), lock screen
  (`dumpsys window` → `isKeyguardShowing` / `mDreamingLockscreen`, or keyguard window focus) and System UI shade.
- PID: `pidof <pkg>`; running-for from `ps -o etime= -p <pid>` (toybox; check API 26).
- Version / debuggable / system / target SDK: `dumpsys package <pkg>` (`versionName`, `versionCode`, `flags=[ DEBUGGABLE SYSTEM ]`, `targetSdk`).
- Restart target: `cmd package resolve-activity --brief <pkg>` for the launcher activity; `am start -W` gives the launch result for error toasts.
- Multi-window / split screen / freeform: several resumed activities — show the one with input focus.
- Work profile / secondary users: the foreground app may be user 10+; pass `--user` to every command.
- Cost of each query over Wi-Fi adb; if > ~300 ms, back off the poll to 5 s.

## Batch 2 — decisions

- **Inspector surface: editor tab.** Measuring a phone screen needs width the 380 px dock can’t give; an editor tab can be split next to code and kept open. The tool window only launches and reports status.
- **Frozen captures only.** uiautomator dumps take 0.5–3 s and need an idle screen; a frozen capture makes redlines and the overlay stable. Re-capture is one click / ⌥⇧⌘I.
- **Audit is a mode, not a view.** It needs the same canvas and selection; a toggle keeps one place to look.
- **Language & Location in the Device view,** under Display scale. Both change what the device thinks its environment is; Network stays about traffic. Language is an override (chip + reset all); location is not (emulators have no real location to restore).
- **Rotation as a segmented control,** not a tile: it has three states and Auto must stay visible as the way back.
- **Connectivity toggles block themselves** when adb runs over Wi-Fi, instead of confirming.
- **Clipboard: Settings checkbox, default on,** stated in the toast — no extra toast action, Reveal stays the one action.
- **MCP: Off by default; Full control still confirms Uninstall and Clear data** in the IDE, naming the agent; 60 s timeout = declined. Read only hides Act tools from the agent instead of failing them. Localhost + bearer token.
- **Shortcuts:** ⌥⇧⌘I capture/open inspector; canvas-scoped single keys G / I / A / F / 1 / ± (Figma-like, no global cost).

**To find out on the device / in the platform**
- uiautomator: `uiautomator dump --compressed` vs. the accessibility service route for Compose semantics (merged nodes, testTag → resource-id needs `testTagsAsResourceId`); dump fails on non-idle screens — fall back to `--windows` or retry with animations off.
- FLAG_SECURE detection: `screencap` returns black; check `dumpsys window` for `FLAG_SECURE` to show the right state.
- Locale change without root: system locale needs CHANGE_CONFIGURATION (helper APK + `pm grant`, or root/`setprop persist.sys.locale` on emulators); per-app `cmd locale set-app-locales` (API 33+) is the no-helper alternative. Check what Oh My Android does and which Android versions it covers.
- Hebrew code: devices may report `iw-IL`; normalise.
- `adb emu geo fix` needs the emulator console auth token (`~/.emulator_console_auth_token`).
- Layout bounds / GPU flags need the `service call activity 1599295570` poke to apply without restarting apps; verify per API.
- Bold text key `font_weight_adjustment` is API 31+; airplane-mode command is API 30+ (`settings put global airplane_mode_on` + broadcast below).
- MCP: streamable HTTP via the platform's built-in server or an embedded Ktor/Netty one; confirm each agent's current config syntax (Codex `bearer_token_env_var`, Gemini `httpUrl`).

## Design tokens

See `tokens/tokens.json` for machine-readable values (dark + light + severity + spacing + type).

Dark: bg `#1e1f22`, panel `#242629`, header `#2b2d30`, field `#1e1f22`, border `rgba(255,255,255,.08)`,
borderStrong `rgba(255,255,255,.15)`, text `#dfe1e5`, textDim `#8a8d93`, textFaint `#6b6e74`,
accent `#548af7`, accentBg `rgba(84,138,247,.16)`, accentBorder `rgba(84,138,247,.5)`,
brand `#16a79b`, green `#66b578`, amber `#d9a441`, red `#e0656b`, hover `rgba(255,255,255,.055)`,
popup shadow `0 20px 50px rgba(0,0,0,.5)`.

Light: bg `#f7f8fa`, panel `#ffffff`, header `#f2f3f5`, border `#e1e3e8`, borderStrong `#cfd2d8`,
text `#1e1f22`, textDim `#63666b`, textFaint `#93969b`, accent `#3574f0`, brand `#0e8a80`,
green `#2f8f52`, amber `#a9761f`, red `#c9424a`, hover `rgba(0,0,0,.045)`.

Semantics — accent = selection / focus / the one primary action per view; brand teal = plugin identity
only (logo, running-command chip), never a button fill; amber = "a non-default value is applied to the
device right now"; red = destructive or error only; green = healthy state and success.

Spacing 2 / 4 / 6 / 8 / 12 / 16. Control heights 22 (icon button, chip, toolbar row) /
24 (field, combo, secondary button) / 26 (primary action, device bar row, rail button).
Radius 4 fields · 5 buttons & chips · 6 rail/icon buttons · 8 cards & popups.
Type 14/600 title · 12.5/700 section · 11.5/400 body · 10.5 caption · 9.5/700 uppercase group label ·
mono 11 (serials, packages, dpi, log) · mono 9–10 (meta).

**New token:** `measure` — inspector redlines and distance labels only: `#F2765A` dark / `#E5583B` light (the logo’s
coral accent; not used anywhere in the tool window).

## Assets

`icons/` contains the production SVGs — icon set v2 (IntelliJ New UI spec, user handoff
2026-10-01): 1px strokes on the half-pixel grid, exact New UI palette (`#6C707E` light,
`#CED0D6` dark; red `#DB3B4B` / `#DB5C5C`) so the IDE recolors them on hover and selection, no
separate selected files, no @2x files. Dark variants use the `_dark.svg` suffix.

- `pluginIcon.svg` / `pluginIcon_dark.svg` — 40×40 Marketplace logo, also bundled as
  `META-INF/pluginIcon(_dark).svg`. No text, shapes clear of the edges.
- `expui/toolwindow/adbToolbox.svg` (16) + `adbToolbox@20x20.svg` (the New UI stripe picks the
  20px sibling automatically) — the logo silhouette in one color, tiles and slider cut out, the
  two "off" tiles at 45%. Registered as `icon="/icons/expui/toolwindow/adbToolbox.svg"`.
- `expui/views/` — rail glyphs: device, apps, network, logcat. The selected rail item recolors
  its glyph to `accent`; rail badges are painted at runtime, never baked into the SVG.
- `expui/actions/` — 16×16 action icons: mirror, screenshot, record, stopRecording, restartApp,
  forceStop, clearData, uninstall, fontScale, density, resetOverrides, proxy, authorize, wifi, usb,
  layoutInspector, a11yAudit, location, language, overlay, mcpAgent, rotate (batch 2, 2026-10-07);
  plus pin, pinned and options kept from set v1 (not covered by v2). Color only for meaning: red for
  live recording and irreversible actions, amber for device overrides.

Not bundled — use the platform icon: refresh device list `AllIcons.Actions.Refresh`, launch app
`Actions.Execute`, pause/resume stream `Actions.Pause`/`Actions.Resume`, autoscroll
`RunConfigurations.Scroll_down`, wrap lines `Actions.ToggleSoftWrap`, filter `General.Filter`,
search `Actions.Search`, clear log buffer `Actions.GC`, settings `General.Settings`, show system
packages `Actions.Show`, more actions `Actions.More`. Load bundled ones with
`IconLoader.getIcon("/icons/expui/…", javaClass)`.

## Files

```
designs/ADB Toolbox Plugin.dc.html          implementation target (interactive prototype)
designs/ADB Toolbox Design System.dc.html   tokens & control specs
designs/ADB Toolbox IA.dc.html              IA, rationale, keyboard, degradation
designs/ADB Toolbox Icons.dc.html           icon specimens & rules
designs/support.js                          prototype runtime only — not for implementation
tokens/tokens.json                          machine-readable tokens
designs/ADB Toolbox Layout Inspector.dc.html  inspector editor tab + accessibility audit
designs/ADB Toolbox Settings.dc.html        Settings page incl. clipboard and AI agents (MCP)
designs/ADB Toolbox Icons v2.dc.html        icon set v2 specimen incl. batch 2 icons
icons/…                                     production SVGs (set v2 under icons/expui)
screenshots/device-picker.png               pixel reference for the device picker (§1, handoff 4)
IMPLEMENTATION.md                           IntelliJ Platform mapping + adb/scrcpy command reference
```
