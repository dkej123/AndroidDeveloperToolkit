# Changelog

All notable changes to ADB Toolbox. Each release needs a `## [<version>] - <YYYY-MM-DD>` section:
the Marketplace change notes and the GitHub release notes are generated from it
(`release/README.md`).

## [1.2.0] - 2026-10-09

### Added
- **AI agents (MCP)**: `screenshot annotate=true` draws numbered boxes on elements — UI-tree elements
  by ref, and shapes the tree does not describe (games, canvas, Flutter) as `m1`, `m2`… for
  `tap`/`swipe mark=` — also when the UI tree cannot be read at all; `tap`, `swipe`, `type_text`
  and `press_key` take `return_ui` to return the next screen in the same call.
- **Reset one permission**: the caret next to Current app › Reset permissions resets all granted
  permissions or just the one you pick.
- **AI agents (MCP)**: `list_permissions` and `set_permission` (grant, revoke or reset one runtime
  permission); `manage_app reset_permissions` takes `permission`.
- **Agent skill**: install a generated `SKILL.md` for Claude Code, Codex CLI or Gemini CLI from
  Settings › AI agents, or from a tip in the tool window that can be closed for good.

### Changed
- **Renamed to Device Cockpit for Android** (formerly ADB Toolbox): the plugin does far more than
  run adb. Same plugin, settings and MCP server name (`adb-toolbox`); the tool window keeps its place.
- UI capture retries longer while the screen animates and suggests turning animations off.
- Current app: the flickering "updated N s ago" label is gone; a refresh button updates on demand.

## [1.1.0] - 2026-10-08

### Added
- **Layout Inspector** (Capture → Inspect layout, ⌥⇧⌘I): the screen and its UI hierarchy in an
  editor tab — sizes and distances in dp, redlines between elements, 8 dp grid, color picker,
  design overlay from a Figma PNG (scale, opacity, Difference, nudge), searchable hierarchy tree
  and attributes with Copy.
- **Accessibility audit** in the inspector: estimated TalkBack order, unlabeled controls, images
  without a description, touch targets under 48 dp, Markdown report.
- **Current app** section: the app in front with its facts, refresh, Kill, Reset permissions and
  Details.
- **Language & region** (per-device locale override with reset) and **Location** (emulator GPS).
- **Quick toggles**: nine new toggles and screen rotation; toggles a device refuses show as n/a.
- **AI agents (MCP)**: an optional local MCP server (127.0.0.1, token) so coding agents such as
  Claude Code, Codex CLI, Cursor or Gemini CLI can use the selected device — read only or full
  control, Uninstall and Clear data always ask first; setup snippets in Settings.
- Screenshots are also copied to the clipboard (Settings › Capture, on by default).

### Changed
- **Screen** section replaces Mirroring and Capture: one toolbar (Mirror, Screenshot, Record) with
  running states on the buttons, mirroring options inline instead of a dialog (new: turn the
  device screen off while mirroring), Full page under the Screenshot button's menu, and Inspect
  layout on its own row.
- **Display** section: font scale and display scale as two dropdowns side by side.
- Device and Network sections can be collapsed; the choice is remembered across projects and restarts.
- The device picker matches the design pixel for pixel, with an "Emulator" chip for emulators.

### Fixed
- App icons were missing on current Android versions (the device could not load adaptive or
  vector icons from the helper process). Icons now also come from the launcher activity and stay
  sharp on HiDPI screens.
- Compatibility with IntelliJ IDEA / Android Studio 2025.2 – 2026.3: no removed, internal or
  experimental platform API is used any more (theme listener, Navigation graphs for deep links,
  Open shell, proxy settings, rail icon tint).

## [1.0.0] - 2026-10-01

First public release.

### Added
- **Device** view: device facts, screenshots and screen recording, scrcpy mirroring with options,
  font scale and display density presets with custom values and reset.
- **Quick toggles**: dark theme, animations, show touches, TalkBack, stay awake, don't keep
  activities, show view/surface updates and the background process limit.
- **Apps** view: searchable package list with launcher icons, pins, system-package filter;
  restart, force-stop, launch, clear data and uninstall.
- **App details**: info, shared preferences and SQLite databases editable in place, runtime
  permissions and deep links (installed-APK analysis, open a URI in the app).
- **Network** view: device-wide HTTP proxy and emulator network throttling.
- **Logcat** view: live stream with level chips, search, package filter, pause, autoscroll and wrap.
- Wi-Fi pairing, multiple devices, light and dark themes, diagnostics bundle.
- Device selector styled like Android Studio's own device picker: device icon with status dot,
  hover highlight, and a click anywhere on it opens the device list.
