# ⏸ Work in progress — resume here

**1.4.1 published 2026-10-14** (tag `v1.4.1` on `e740561`, Publish Plugin run 38568734715 dispatched on
the tag; GitHub release by hand): Mirror opens a dialog with the mirroring options and Start mirroring,
the caret under Mirror is gone (`b165fc6`). E2E on pool emu-1: 13/14 mirroring + settings tests pass;
Show touches fails only because scrcpy cannot open an audio device in this crate (pool emulator has
audio). `release/test.sh` fails `device-picker-dark-wide` by 0.031 pp only with `/work/.e2e-env.sh`
loaded (its fonts.conf renders the mono serials differently); without it the full gate passes.
Waiting for JetBrains moderation.

**1.4.0 published 2026-10-13** (tag `v1.4.0` on `4daa551`, Publish Plugin run 38468424787 dispatched on
the tag; GitHub release by hand): Mirror/Screenshot/Record on the rail above Settings, Accessibility audit
button next to Inspect layout, `accessibility_audit` refs + dp, `screenshot annotate` legend, Record
through scrcpy 2.4+ (no 180 s limit; screenrecord fallback without scrcpy and on Windows). Verified: a
211 s scrcpy recording stopped with SIGTERM is a finished MP4. E2E CaptureAndMirroring on the local TCG
AVD: Record passes; Screenshot (screencap 15 s timeout) and Show touches (settings read timeout) failed
on the overloaded emulator only. The pool phone (phone-bridge, remote adb server) cannot run scrcpy:
its tunnel opens on the bridge's localhost.
Open ideas (user said not to change yet): per-device TalkBack wording (Samsung vs Pixel — real
`AccessibilityNodeInfo` via the helper, Google TalkBack's open-source compositor rules, device locale).

**1.3.0 published 2026-10-13** (tag `v1.3.0` on `7ed6961`, Publish Plugin run 38368279396 — first
attempt failed on a flaky `LogcatCoordinatorTest` badge test (5 s deadline), the rerun passed;
GitHub release by hand; store screenshots unchanged from 1.2.0). Waiting for JetBrains moderation.

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
