# Changelog

All notable changes to Device Cockpit for Android (formerly ADB Toolbox). Each release needs a `## [<version>] - <YYYY-MM-DD>` section:
the Marketplace change notes and the GitHub release notes are generated from it
(`release/README.md`).

## [1.5.0] - 2026-10-29

### Added
- **Open deep link**: a Deep link button in the Device view (and Find Action) opens a dialog that fires any
  link on the device the way a tap would (`am start -W`, VIEW + BROWSABLE). It has a Recent list of 50 links,
  query parameters kept in sync with the URI, "Open in" any app or a specific one, and a one-line command
  preview with Expand and Copy. The dialog stays open after a launch.
- **Who handles this link?**: while you type, the dialog asks the device (without launching anything)
  whether the link opens directly, shows the chooser, falls back to the browser because an App Link is not
  verified, or is not handled at all; the matching apps are one click away.
- **Launch result in the dialog**: the app that really took the link, cold/warm start and time, the
  chooser, delivery to an already running activity (with a one-click Cold start), and adb errors with the
  raw output to copy. A warning explains that a specific app skips App Link verification.
- **Cold start** option (`am start -S`) for deep links.
- Copy report in the Device section now confirms with a toast.

### Changed
- Recent deep links that went to one app show that app's name and icon; "Any app", system apps and the
  home / lock screens show the Android robot.
- Toasts are rounded cards with text actions.
- Setting a device language keeps the other languages of the device, and Reset restores their original order.

### Fixed
- Launch, Restart and Process death opened a random launcher activity when an app declares several (for example
  LeakCanary's); they now open the app's main launcher activity, as tapping its icon does.
- Reset permissions listed only the permissions granted when the app came to the front; the menu and Refresh now
  read them again, so permissions granted meanwhile (in App details or on the phone) show up.
- TalkBack could not be turned on in Android Studio, and turning it on or off no longer switches off other
  accessibility services.
- The app list, labels and icons load as soon as the phone is connected at IDE start, not only after
  opening Apps.

## [1.4.2] - 2026-10-27

### Changed
- **App tools & details**: a full-width clickable bar in Current app opens the app's details,
  listing Deep links, Permissions, Shared prefs and Databases.

### Fixed
- Added spacing below the back link on the app details page.

## [1.4.1] - 2026-10-14

### Changed
- **Mirror opens a dialog**: clicking Mirror shows the mirroring options (resolution, bitrate, stay
  awake, show touches, screen off) with a Start mirroring button; the arrow under Mirror is gone.
  While mirroring, the button still stops it at once, and the keyboard shortcut starts straight away
  with the saved options.

## [1.4.0] - 2026-10-13

### Added
- **Accessibility audit button**: next to Inspect layout in the Device view. It captures the screen
  into a new Inspector tab that opens straight on the TalkBack order and its problems.
- **Recordings longer than 3 minutes**: with scrcpy 2.4 or newer installed, Record runs scrcpy in the
  background (no window) and writes the MP4 straight to the capture folder, without Android's
  180-second `screenrecord` limit. Without scrcpy, and on Windows, Record still uses `screenrecord`.

### Changed
- **Mirror, Screenshot and Record on the rail**: the screen buttons moved from the Device view to the
  left rail, above Settings, so they work from every view. The arrows under Mirror and Screenshot
  still open the mirroring options (in the Device view) and the Full page screenshot.
- **`accessibility_audit` for AI agents**: each stop now carries its `get_ui` ref and frame in dp, so
  an agent can tap the element or find it on a screenshot. The tool description says the audit
  approximates TalkBack and vendor builds such as Samsung's may word things differently.
- **`screenshot annotate=true`** lists what each numbered box is, so the agent no longer needs a
  separate `get_ui` call to read it.

### Fixed
- `accessibility_audit` listed images without a description in pixels, unlike every other tool;
  it now uses dp.

## [1.3.0] - 2026-10-13

### Added
- **Deep Links, one row per link**: every scheme × host × path an intent filter accepts is its own
  row, as a template such as `app://acme/user/{userId}?tab={tab}`, grouped by activity in the same
  list style as Permissions — chips for app-link state (Verified, App link), Navigation and warnings.
  **Open…** unfolds a launcher under the row: a field per parameter (exact names, types and defaults
  from Navigation graphs; otherwise inferred from `pathPattern`/`pathPrefix` wildcards), **Add
  parameter** for any query parameter, a live URI preview, **Open**, **Copy URI** and **Copy adb command**.
- **Deep Links remember what you typed**: each link's parameter values and added parameters are kept
  per project and come back when you select it again, also after restarting the IDE.

## [1.2.0] - 2026-10-12

### Added
- **First-run tip**: the amber tip at the top of the tool window now shows from the first run until agents
  are set up; one click turns the MCP server on (Full control — Uninstall and Clear data still ask) and
  connects Claude Code with its skill.
- **Connect Claude Code**: one button in Settings › AI agents registers the running MCP server with
  Claude Code (`claude mcp add`, user scope) and installs the agent skill — no snippet to paste.
- **Process death**: Current app › Process death sends the app home, lets Android kill it in the
  background (`am kill`) and reopens it, so its activities are recreated from saved state — the
  `SavedStateHandle` / `onSaveInstanceState` path "Don't keep activities" does not exercise. Also
  `manage_app process_death` for AI agents.
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
  run adb. Same plugin and settings; the tool window keeps its place.
- UI capture retries longer while the screen animates and suggests turning animations off.
- Current app: the flickering "updated N s ago" label is gone; a refresh button updates on demand.
- App details › Permissions is a grouped list instead of a table: runtime permissions with state
  chips, flags and Grant/Revoke/reset on each row, install-time ones folded away, a filter and
  **Reset all**.
- **AI agents (MCP)**: the server and the agent skill are now called `device-cockpit` (setup
  snippets, `serverInfo`, `SKILL.md`; the Codex token variable is `DEVICE_COCKPIT_TOKEN`).
  Installing the skill removes the old `adb-toolbox` copy. Servers registered as `adb-toolbox`
  keep working — the name is only the key in the agent's config.

### Fixed
- Current app › Reset permissions' caret opens the IDE's own list popup — a title, "All granted
  permissions (N)", a separator and readable names ("Post notifications") with speed search —
  instead of a bare Swing menu.
- Current app showed the last part of the package name ("Debug") and an empty tile for an app the
  Apps list had not loaded yet, such as one just installed from the IDE; it now reads the app's own
  name and icon from the device.
- Current app › Process: the running time counts up every second instead of freezing at the
  value read when the app started.
- App names and icons were missing on Samsung phones with Android 16 (the on-device helper crashed);
  old helper copies are now removed from the device when a new one is installed.
- App names and icons stopped after the first few dozen apps on Samsung phones: rendering an icon
  through the vendor's icon theming aborted the on-device helper. Icons now load from each APK's
  own resources (split APKs included); should a phone still kill the helper on one app, the rest
  are fetched again and only that app goes without its icon. A full list takes about 2 s.
- Current app no longer waits for "Update" while the mouse pointer just rests over it.
- Logcat with line wrap no longer keeps the IDE busy at 100% CPU: the list shows the newest 20,000
  lines (search still covers the whole buffer), measures only new lines, and renders nothing while
  Logcat is not on screen — it catches up when you open it.
- Settings › Logcat buffer size now takes effect (it was ignored and the buffer was always 16 MB),
  also while Logcat runs; the default is 1 MB.

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
