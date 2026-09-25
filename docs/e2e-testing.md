# End-to-end tests (real Android Studio + emulator)

The `:e2e` module tests the plugin the way a user uses it: the plugin ZIP is installed into a real
**Android Studio**, a real **Android emulator** is attached, and every feature is driven through the
actual Swing UI. Every effect is then verified independently on the device with plain `adb`
(`settings get`, `wm density`, `pidof`, …) and in the plugin's diagnostics log. Performance tests
measure the IDE process from the outside (per-thread CPU, UI-thread latency).

Unit/platform tests (`./gradlew test`) stay the fast gate. The E2E suite is the gate for "does it
actually work in Android Studio", and it is what caught, e.g., the adblib timeout spin and the
O(buffer) Logcat layout that no unit test saw.

> Android developers use Android Studio, not IntelliJ IDEA + the Android plugin, so the suite runs
> against Android Studio. Only in Android Studio does the plugin take the ddmlib/adblib transport
> path (in `runIde`, the pinned IDEA build rejects the Android plugin and only binary adb is used).

## TL;DR for a fresh session

```bash
cd AndroidDeveloperToolkit
e2e/scripts/run-e2e.sh                       # provision what is missing, start everything, run all tests
e2e/scripts/run-e2e.sh -Pe2e.tags=smoke      # just the smoke tests
e2e/scripts/run-e2e.sh --setup-only          # start the environment and leave it running
./gradlew :e2e:e2eTest --tests '*LogcatE2ETest*'   # re-run against a running environment
```

Everything is installed **without root** under `$E2E_HOME` (default `~/.cache/adb-toolbox-e2e`).
In a disposable container where only one directory persists, point it there, e.g.
`export E2E_HOME=/work/.e2e`, so the ~3 GB of downloads survive the next session.

The first run downloads the SDK image and Android Studio (~3 GB) and boots the emulator; on a
machine without KVM expect ~15 min of setup and ~3-4 min per emulator boot. Later runs reuse
everything (`run-e2e.sh` is idempotent) and only rebuild/reinstall the plugin and restart Studio.

## Requirements

| Requirement | Why | Notes |
|---|---|---|
| Linux x86_64 | emulator + Studio binaries | Tested on Ubuntu 24.04. macOS is not scripted. |
| JDK 21 (`JAVA_HOME`) | Gradle, sdkmanager | Same JDK the project build uses. |
| `curl unzip tar python3 dpkg-deb` | downloads, user-space X11 install | Present on stock Ubuntu. |
| ~8 GB free disk, 8+ GB RAM, 4 cores | emulator (2 GB) + Studio (2-3 GB) | |
| Internet access | dl.google.com, f-droid.org, github.com, packages.jetbrains.team, archive.ubuntu.com, Maven Central | Only on first run. |
| `/dev/kvm` (optional) | hardware-accelerated emulator | Without it the emulator runs with `-accel off` (software CPU); everything works, ~5-10× slower. Timeouts scale automatically (`E2E_TIMEOUT_SCALE`, default 4 without KVM). |
| No root, no Docker | — | Deliberately not needed. |

## What the scripts install and where

All paths are overridable environment variables (see `e2e/scripts/lib.sh`); the defaults are:

| What | Script | Default location | Pinned version |
|---|---|---|---|
| Xvfb, xdotool, xwd, openbox, X11 client libs | `setup-x11.sh` | `$E2E_HOME/x11` (skipped if a system `Xvfb` exists) | Ubuntu 24.04 packages |
| Android cmdline-tools, platform-tools, emulator | `setup-android.sh` | `$E2E_HOME/android-sdk` | latest from sdkmanager |
| System image + AVD `adb-toolbox-e2e` (Pixel 3 profile) | `setup-android.sh` | `$E2E_HOME/avd` | `system-images;android-28;default;x86` |
| scrcpy (mirroring tests) | `setup-android.sh` | `$E2E_HOME/scrcpy-linux-x86_64-v4.1` | v4.1 |
| Fixture apps (Apps tests) | `setup-android.sh` | `$E2E_HOME/fixtures` | `e2e/fixtures/apks.txt` (F-Droid version codes) |
| Android Studio | `setup-studio.sh` | `$E2E_HOME/android-studio` | Quail 4, 2026.1.4.7 (`E2E_STUDIO_VERSION`/`E2E_STUDIO_URL`) |
| JetBrains robot-server plugin | `setup-studio.sh` | `$E2E_HOME/robot-server-plugin-0.11.23` | 0.11.23 |
| Studio config/plugins sandbox | `start-studio.sh` | `$E2E_HOME/studio-sandbox` | recreated every start |

Why these choices:
- **API 28 `default` x86 image**: the newest image that boots reasonably without KVM, and the
  non-Google image allows `adb root`, which the Apps tests use to plant/verify app data.
- **User-space X11**: Xvfb's keymap compiler path is compiled in as `/usr/bin`; `setup-x11.sh`
  patches that one string to `/tmp/xkb` (a symlink `start-display.sh` creates). No root needed.
- **Fresh Studio config per start**: deterministic plugin settings; consent/tips/trust dialogs are
  disabled via VM options (`start-studio.sh`), Google's usage-statistics prompt via
  `~/.android/analytics.settings`.

## Scripts

| Script | Does |
|---|---|
| `run-e2e.sh` | All of the below in order, then `./gradlew :e2e:e2eTest`. `--setup-only` stops before the tests. `E2E_RESTART_STUDIO=0` reuses a running Studio. |
| `setup-x11.sh` / `setup-android.sh` / `setup-studio.sh` | Idempotent provisioning (download only what is missing). |
| `start-display.sh` | Xvfb on `:99` (1920×1080) + openbox. |
| `start-emulator.sh` | Boots the AVD headless unless already booted; `adb root`; screen stay-on; 8 MB logcat buffer; installs fixtures. |
| `start-studio.sh` | Builds the plugin ZIP (`E2E_SKIP_BUILD=1` to skip), installs it + robot-server into a fresh sandbox, launches Studio on the test project, waits for the robot endpoint and the project frame. |
| `stop-studio.sh` / `stop-emulator.sh` | Teardown. |

Useful variables: `E2E_HOME`, `E2E_DISPLAY` (`:99`), `E2E_ROBOT_PORT` (`8082`),
`E2E_DEVICE_SERIAL` (`emulator-5554`), `E2E_EMULATOR_ACCEL` (`auto`/`off`), `E2E_TIMEOUT_SCALE`,
`ANDROID_SDK_ROOT`, `E2E_STUDIO_HOME` (reuse an existing Studio), `E2E_SKIP_BUILD`.

## Running

- All non-destructive tests: `./gradlew :e2e:e2eTest` (needs the environment from `run-e2e.sh --setup-only`).
- By tag: `-Pe2e.tags=smoke`, `-Pe2e.tags=perf`, `-Pe2e.tags=destructive` (reboots the device; excluded by default).
- One class/test: `--tests '*AppsE2ETest*'`.
- `./gradlew build`/`check` only **compile** the E2E tests; the regular `test` task of `:e2e` is disabled.
- Watch the screen: `DISPLAY=:99 xwd -root | …`, or use the failure screenshots below.
- Inspect the live UI tree (to write selectors): open `http://127.0.0.1:8082/hierarchy` (or `curl` it).

### When a test fails

`e2e/build/e2e-report/failures/<Class>-<test>.{png,hierarchy.html,plugin-log.txt}`: a screenshot,
the full Swing hierarchy at that moment, and the last 300 plugin-log lines. The HTML report is at
`e2e/build/reports/tests/e2eTest/index.html`. Performance numbers are appended to
`e2e/build/e2e-report/perf.txt`. Studio's own logs: `$E2E_HOME/studio-sandbox/log/`
(`idea.log`, `adb-toolbox/adb-toolbox.log`).

## How the tests are built

```
e2e/src/test/kotlin/dev/acme/adbtoolbox/e2e/
  infra/E2eConfig.kt   environment (from lib.sh variables), timeout scaling
  infra/Studio.kt      UI driver: find by accessible name, rail navigation, dialogs, texts, clipboard
  infra/Adb.kt         ground truth on the device (independent of the plugin)
  infra/PluginLog.kt   reads adb-toolbox.log between marks (commands, errors)
  infra/IdeProbes.kt   per-thread CPU from /proc, EDT round-trip latency
  infra/E2eTest.kt     base class: env check, tool window + device ready, no plugin ERRORs, failure artifacts
  tests/*E2ETest.kt    one class per feature area
```

Rules for new tests:
- **Act through the UI, assert on the device.** Click the control a user would click; verify with
  `Adb` (or the file system), not with the plugin's own labels alone.
- **Select by accessible name** (`studio.byName("Enable proxy")`). Every control has one (task 050);
  a control without one is an accessibility bug — add the name in production code, don't use
  coordinates. Use `@name` for dialog fields that have a component name.
- **Restore device state** in `@AfterEach` (font scale, density, proxy, installed fixtures…).
  State changed with `Adb` is a change *outside* the plugin: call
  `studio.leaveAndReturnToToolWindow()` afterwards, as a user coming back from a terminal would, so
  the view re-reads the device.
- `studio.click(name)` waits until the control is enabled — the plugin disables controls while it
  writes and re-reads device state. Never click raw fixtures for those controls.
- Never touch the IDE (mouse, keyboard, robot scripts) while a run is in progress; it steals focus
  and fails whichever test is running.
- **Scale device waits** with `E2eConfig.deviceTimeout(seconds)`; UI-only waits use fixed seconds.
- In robot scripts, `instanceof javax.swing.X` silently evaluates to `false` inside a component's
  script scope; use `java.lang.Class.forName("javax.swing.X").isInstance(c)`.
- Do not ship large data over the robot HTTP API (e.g. 100k Logcat rows) — compute in the IDE and
  return a number (see `LogcatE2ETest.count`).

## Test inventory

| Class | Covers |
|---|---|
| `ToolWindowSmokeE2ETest` (smoke) | tool window opens, emulator selected, every rail view renders, ddmlib transport active |
| `DeviceE2ETest` | model name, all six facts vs `getprop`/`wm`/`dumpsys`, Copy report, refresh, picker, Wake, Open shell |
| `CaptureAndMirroringE2ETest` | Screenshot PNG size, Record → MP4, scrcpy version label, start/stop mirroring, mirroring options passed to scrcpy |
| `AppsE2ETest` | listing, app installed outside the plugin (Run) appears on return, labels and icons, filter, system packages, Launch, Force-stop, Restart, Clear data (cancel/confirm), Uninstall |
| `DisplayE2ETest` | font presets/reset/custom + validation, density presets/reset/custom, dark theme, animations off, show touches, toggle values |
| `NetworkE2ETest` | proxy enable/disable, invalid port, Recent fills the form (per design §6), host IP fill |
| `LogcatE2ETest` | follow on open, live lines, search, level filter, pause/resume, clear, footer |
| `SettingsE2ETest` | rail opens the plugin page, capture directory used by Screenshot and shown in Device view, adb path validation, persistence |
| `ShortcutsAndDiagnosticsE2ETest` | Ctrl+Shift+L / Ctrl+Shift+M, platform-correct shortcut hints, Collect Diagnostics ZIP |
| `DeviceLifecycleE2ETest` | Wi-Fi pairing dialog; (destructive) reboot round trip and reselection |
| `PerformanceE2ETest` (perf) | idle CPU, EDT idle after a 20k-line Logcat flood, EDT latency during flood and while searching, system-package listing |

## Tests expected to fail until a product decision

| Test | Why it fails | What is needed |
|---|---|---|
| `AppsE2ETest › rows show the app label, not only the package name` | `dumpsys package` prints no application label on stock Android (only `labelRes=0x…` is available on-device), so every row falls back to the package name (ADR 0007 fallback). | A label source: pull the APK and read `resources.arsc`, or use the SDK's `aapt2 dump badging`. Changes ADR 0007's approved strategy. |

On a device without hardware acceleration some plugin-side command timeouts (e.g. 5 s for
`wm density`/`cmd uimode night`, 10 s for `monkey`) can expire; the affected tests then fail with
the plugin's own "Command timed out" toast. That is the environment (the same commands take
~0.1–0.3 s on a phone), not a regression — re-run with KVM. Seen in practice without KVM:
`DisplayE2ETest › dark theme toggle switches ui night mode` (the read-back after "yes" times out, so
the next click sends "yes" again) and `DisplayE2ETest › density preset overrides and Reset to
physical restores it` (the read-back after `wm density 484` times out, so no Reset link appears).

## Known environment quirks

- Without KVM the emulator kills itself when starved of CPU ("detected a hanging thread 'QEMU2
  CPU1 thread'" in `$E2E_LOGS/emulator.log`). Do not run a Gradle build, a second provisioning or
  other heavy jobs while it runs; if it died, `e2e/scripts/start-emulator.sh` boots it again.

- The JVM needs a UTF-8 locale: some test class names contain "—", and under a POSIX locale Gradle
  fails with `Failed to create MD5 hash … (No such file or directory)`. `lib.sh` exports
  `LC_ALL=C.UTF-8`; if you run Gradle by hand, export it too and `./gradlew --stop` a daemon that
  was started without it.
- Keep the unit/platform gate (`./gradlew build koverVerify architectureCheck`) in a shell **without**
  `lib.sh` sourced: a few `:intellij` platform tests (e.g. `MirroringToggleActionTest`) assume no
  device is reachable and fail when the emulator is online and adb is on `PATH`.
- Headless platform tests need `libfreetype`/fontconfig; on a bare container point
  `LD_LIBRARY_PATH`/`FONTCONFIG_FILE` at a user-space copy (as for Studio in `setup-x11.sh`).
- The first Studio start after a fresh sandbox can take several minutes (indexing, Gemini panel);
  `start-studio.sh` waits up to 15 min for the project frame.

- Without KVM, individual `adb shell` calls take 0.2-10 s. Plugin-side fixed timeouts (e.g. 5 s for
  `wm density`) can expire; the suite scales only its own waits.
- `adb root` restarts adbd; commands in flight at that moment fail once (seen as a toast).
- The suite hides bottom tool windows (Terminal, Run, …) before each test because they shrink the
  plugin panel.
- Recording relies on the device's `screenrecord`; on the software-rendered emulator it works but
  produces small files.
