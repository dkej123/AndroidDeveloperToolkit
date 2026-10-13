# ⏸ Work in progress — resume here

**1.2.0 published 2026-10-13** (tag `v1.2.0` on `1062379`, Publish Plugin run 38358892312, GitHub
release, store screenshots `marketplace/screenshots/1.2.0`); waiting for JetBrains moderation. The
tag push did not start the workflows (the tagged commit had `[skip ci]`): Publish Plugin was
dispatched on the tag and the GitHub release created by hand. Full E2E 2026-10-13 on the local
software-emulated AVD: Logcat autoscroll bug fixed (`f2b142b`), MCP tip test updated; still failing
only on this machine: Display dark theme, Settings capture directory (screencap timeout), MCP
marked-screenshot tap (System UI ANR dialog on the emulator).

## What is in `main` (unreleased 1.2.0)

| Commit | Content |
|---|---|
| `7803e9d` | Task 066: UI-capture backoff, `screenshot annotate` + `tap/swipe mark=N`, `return_ui`, generated agent skill (`SKILL.md`) installed from Settings › AI agents and from an amber, dismissible tip in the tool window |
| `e634d95` | Version 1.2.0 (`gradle.properties`, `CHANGELOG.md`) |
| `eec4f6a` | `screenshot` without a UI tree (`PngSize`, `CaptureLayoutUseCase.screen()`), new `McpE2ETest` |
| `0642a01` | Renamed to **Device Cockpit for Android** (user-facing only). Kept: plugin id `com.github.dkwasniak.adbtoolbox`, tool-window id `ADB Toolbox`, MCP server name `adb-toolbox`, skill folder `adb-toolbox`, password-safe key |
| `4a98a7c` | Packages `dev.acme.adbtoolbox` → `io.github.dkej123.devicecockpit`. Kept ids: action `dev.acme.adbtoolbox.ToggleMirroring`, configurable `dev.acme.adbtoolbox.settings`, E2E fixture app package |
| `e731ead` | Task 067: reset one permission or all (Current app caret menu), MCP `list_permissions` / `set_permission`, Current app header without the flickering "updated N s ago" |

`./gradlew clean build koverVerify` is green on `e731ead`. The E2E fix in `AppDetailsE2ETest`
(scaled lookup timeout) is in the commit that added this file.

## Where to resume

1. **E2E is incomplete** (`docs/e2e-testing.md`). Two runs on a software-emulated (no KVM) device
   were stopped because the host was out of memory (swap full, load ~15); every failure was a
   timeout.
   - Never run yet: **`McpE2ETest`** (new), `LogcatE2ETest`, `NetworkE2ETest`, `PerformanceE2ETest`,
     `SettingsE2ETest`, `ShortcutsAndDiagnosticsE2ETest`, `ToolWindowSmokeE2ETest`.
   - Failed with timeouts: `AppDetailsE2ETest` shared pref (test fixed, re-run),
     `DeepLinksPermissionsE2ETest` Analyze APK → Open, `DeviceE2ETest` Wake,
     `DisplayE2ETest` dark theme (known on software emulation).
   - **Check:** `DeepLinksPermissionsE2ETest` Grant passed in run 1 but failed in run 2 with no
     `pm grant` in the plugin log, so the click never reached the plugin. Re-run it on an idle machine first; if
     it fails again, debug the Grant click after the package rename.
   - Missing E2E coverage: the Reset permissions caret menu and `list_permissions` /
     `set_permission` (the fixture app has CAMERA).
   - Suggested order: `McpE2ETest`, `DeepLinksPermissionsE2ETest`, `AppDetailsE2ETest`, then the
     never-run classes, then Device/Display.
   - `McpE2ETest` skips the skill-tip test when an `adb-toolbox` skill is already installed in
     `~/.claude`, `~/.codex` or `~/.gemini`.
2. Build the plugin ZIP locally only after E2E: `./gradlew :intellij:buildPlugin`.
3. Release (`release/README.md`) only on the owner's go-ahead. Before it: trademark check of
   "Device Cockpit" (EUIPO/USPTO, class 9), and after publishing check that the old Marketplace link
   still redirects.

## Open decisions / next tasks

- Rename the GitHub repo and the ZIP file name (`adb-toolbox-*.zip`, from the Gradle project name)?
  Not decided.
- Next task: an on-device helper that reads the UI tree through UiAutomation without waiting for
  idle, as a fallback when `uiautomator dump` refuses on animating screens. Start with a short
  check that it works on API 28–35.
- Build note: set `LC_ALL=C.UTF-8` (some test class names are not ASCII).
