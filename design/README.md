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

**Device picker popup** (click the selector): absolutely positioned `top: 62, left: 8, right: 8`,
`panel` background, 1px `borderStrong`, radius 8, popup shadow. Uppercase header "Connected devices"
(9.5px/700, letter-spacing .5, `textFaint`, padding 7px 10px 4px). Rows 28px: state dot (green online /
amber unauthorized) · name (11.5px, 700 when selected) · serial (mono 9.5px, flex, ellipsis) ·
connection chip. Selected row background `accentBg`. Footer on `header` background with a
"Pair device over Wi-Fi…" link and the hint "↑↓ to select · ⏎ to apply" (mono 9px).
In the platform, implement as a `JBPopup` list; keep ↑↓/Enter/Esc working.

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

Three sections, each `padding: 10px 0 12px` with a 1px bottom border; section header row
`padding: 0 10px`, title 12.5px/700, right-aligned meta in mono 9.5px `textFaint`.

1. **Mirroring** — meta "scrcpy 2.7".
   - idle: primary button **"Start mirroring"** (26px, accent, radius 5, 11.5px/600,
     tooltip "Start scrcpy for the selected device  ⇧⌘M") + 22px options icon button
     (tooltip "Mirroring options — bitrate, resolution, stay awake").
     Help text (10.5px `textFaint`, padding 0 10px 2px): "Launches Genymobile scrcpy. Turn on
     “stay awake” and “show touches” in options."
   - scrcpy missing (user decision, 2026-09-29 — supersedes the toast-only story below): resolved
     up front; **"Start mirroring"** is disabled (grey) and both its tooltip and the help text say why
     and how to fix it, e.g. "scrcpy is not installed, or not on PATH. Install it (brew install
     scrcpy) or set its path in Settings." (per OS: `brew install scrcpy`, `winget install --exact
     Genymobile.scrcpy`, `sudo apt install scrcpy`; a broken Settings path says "Fix or clear it in
     Settings."). Below it, link buttons **Open Settings · Check again · Install guide**.
   - running: teal banner row (margin 0 10px, padding 6px 8px, radius 5, `brandBg`, 1px `brandBorder`):
     pulsing 7px teal dot + "Mirroring · 1080×2400 @ 60 fps" (11.5px/600 teal) + outlined red
     **"Stop"** (24px). Help text: "Window is open on your desktop. Closing it also stops this session."
2. **Capture** — meta "~/Desktop".
   - idle: two secondary buttons **"Screenshot"** (tooltip "Save a PNG to ~/Desktop") and
     **"Record"** (tooltip "Record the screen — max 3 minutes per adb").
   - recording: red banner row (`redBg`, 1px `redBorder`) with pulsing red dot +
     mono "Recording · 00:42" (11px/700 red) + outlined red **"Stop & save"**.
     On stop: toast "Saved screen-2026-09-01.mp4" with a **Reveal** action.
3. **Device** — header link "Copy report". Facts grid (3 cols, 2 when narrow, gap 8, padding 2px 10px 6px):
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

1. **Font scale** — header meta mono 10px: "default" or "1.15× applied" (amber when overridden).
   Chip row (gap 4, wrap, padding 0 10px): `0.85× 1× 1.15× 1.3× 1.5× 2×` + dashed **"Custom…"**.
   Chip = 22px, radius 5, padding 0 9px, 11px. Unselected: `textDim` + 1px `border`.
   Selected & default (1×): `text` + 700 + 1px `borderStrong`.
   Selected & override: amber text + 700 + `amberBg` + 1px amber.
   Tooltip on each preset: "settings put system font_scale <v>" (1× → "Device default").
   Custom disclosure: 64px mono field + "×" unit + secondary **Apply**; invalid shows "0.25–5.0" in red.
   When overridden: row with "Overriding device default (1×)" (10.5px `textFaint`) + **Reset** link.
2. **Display scale** — header meta mono 10px "428 dpi" (amber when overridden).
   Chips `80% 90% 100% 110% 125% 150%` + dashed **"Custom…"** (absolute dpi field, unit "dpi").
   Preset tooltips show the resolved value, e.g. 125% → "535 dpi"; 100% → "Physical density — 428 dpi".
   Help: "Percentages are relative to the physical density (428 dpi). Values outside 60–200% can make
   the UI unusable." When overridden: "Physical density is 428 dpi" + **"Reset to physical"** link.
3. **Quick toggles** — tile redesign (2026-09-30, user-supplied prototype revision). Header meta
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
- last command message, 9.5px `textDim`, ellipsised;
- right side: override chip "3 overrides · reset all" (9px/700 amber, 1px amber border, radius 3,
  clickable — reverts font scale, density and proxy on the current device;
  tooltip "Revert font scale, density and proxy on this device") and mono 9px "adb 35.0.2".

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
- **Device disconnect:** remember applied overrides per serial and offer to re-apply when that serial
  returns.
- **Animations:** `adb-spin` 0.7s linear infinite (spinners); `adb-pulse` 1.2–1.6s ease-in-out infinite
  (live dots, skeletons); toggle knob `left` transition 0.15s. Nothing else animates.
- **Keyboard:** Tab/⇧Tab between regions (device bar → rail → view → status bar); arrows within a region;
  ⌘F focuses Logcat search, Esc clears then blurs; Space toggles Logcat pause; End resumes autoscroll and
  jumps to the newest line; Enter applies a custom value.
  Suggested global shortcuts: ⇧⌘M mirror, ⇧⌘R restart app, ⇧⌘L focus Logcat, ⌘⇧D refresh devices.

## State model

Per project (persist with `PersistentStateComponent`): `selectedDeviceSerial`, `selectedPackage`,
`proxyHost`, `proxyPort`, `recentProxies`, `logMinLevel`, `logPackageFilterOn`, `logWrap`,
`captureDir`, `adbPath`, `scrcpyPath`, `lastView`.

Runtime (view models): `devices[]` + `deviceState = connected|loading|unauthorized|none`,
`pickerOpen`, `apps[]` + `appQuery`, `fontScale`, `density`, `darkMode`, `animations`, `showTouches`,
`proxyEnabled`, `mirroringProcess`, `recordingProcess` + elapsed, `logBuffer`, `logQuery`, `paused`,
`autoscroll`, `toasts[]`, `pendingConfirm`.

Derived: `overrideCount = (fontScale != 1) + (density != 100) + proxyEnabled` → status-bar chip and
the amber rail badges. Everything device-touching is disabled when `deviceState != connected`.

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

## Assets

`icons/` contains production SVGs, all drawn on a 1px grid with 1.3–1.35px strokes, round caps,
no text, no gradients:

- `pluginIcon.svg` / `pluginIcon_dark.svg` — 40×40 Marketplace logo (filled; teal `#0e8a80` light,
  `#16a79b` dark). Concept: a toolbox whose handle is an Android head. Eyes are dropped below 24px,
  antennae below 20px — see `pluginIcon_16.svg`.
- `adbToolbox.svg` / `adbToolbox_dark.svg` — 20×20 tool window (stripe) icon, stroke-only, monochrome,
  no brand color, per New UI convention.
- `adbToolbox_16.svg` / `adbToolbox_16_dark.svg` — 16×16 variants.
- `actions/` — the 16×16 action icons (refresh, mirror, screenshot, record, restart, forceStop,
  uninstall, clearData, proxy, fontScale, density, pause, resume, autoscroll, wrap, filter, search,
  options). Only three carry color: record (red dot), uninstall and clearData (red) — the two
  irreversible actions.

Prefer platform `AllIcons` where an exact equivalent exists (search, filter, settings, trash);
bundle the rest. Load with `IconLoader.getIcon("/icons/…", javaClass)`; the `_dark` suffix is picked
up automatically.

## Files

```
designs/ADB Toolbox Plugin.dc.html          implementation target (interactive prototype)
designs/ADB Toolbox Design System.dc.html   tokens & control specs
designs/ADB Toolbox IA.dc.html              IA, rationale, keyboard, degradation
designs/ADB Toolbox Icons.dc.html           icon specimens & rules
designs/support.js                          prototype runtime only — not for implementation
tokens/tokens.json                          machine-readable tokens
icons/…                                     production SVGs
IMPLEMENTATION.md                           IntelliJ Platform mapping + adb/scrcpy command reference
```
