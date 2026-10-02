# Changelog

All notable changes to ADB Toolbox. Each release needs a `## [<version>] - <YYYY-MM-DD>` section:
the Marketplace change notes and the GitHub release notes are generated from it
(`release/README.md`).

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
