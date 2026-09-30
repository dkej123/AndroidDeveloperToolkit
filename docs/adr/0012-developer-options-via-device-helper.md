# 0012 — Developer-options toggles, and the activity-manager ones via the device helper

## Status

Accepted (2026-09-29). Extends ADR 0010's device helper; ADR 0005's transport rules are unchanged.

## Context

The user asked for more Developer options in the Device view's Quick toggles: stay awake, don't
keep activities, show view updates, show surface updates and the background process limit
(standard, 0–4). Checked against the API 28 emulator as the shell user (`adb unroot`):

- **Stay awake** — `svc power stayon true|false` writes `stay_on_while_plugged_in`; plain shell works.
- **Show view updates** — `setprop debug.hwui.show_dirty_regions`, which apps only re-read after
  the activity manager's `SYSPROPS_TRANSACTION` (`service call activity 1599295570`), which it
  forwards to every app. Plain shell works.
- **Show surface updates** — SurfaceFlinger private transactions 1002 (set; `0` *toggles*) and
  1010 (read). SurfaceFlinger requires `HARDWARE_TEST`, which the shell user lacks: as shell the
  reply is `Operation not permitted`; with `adb root` it works.
- **Don't keep activities** — `settings put global always_finish_activities 1` changes the setting
  but the running activity manager never notices: a backgrounded activity stays `STOPPED`. Only
  `IActivityManager.setAlwaysFinish(true)` makes it `DESTROYED`.
- **Background process limit** — held only in the activity manager (`setProcessLimit`,
  `getProcessLimit`); there is no setting at all. The shell user holds `SET_PROCESS_LIMIT` and
  `SET_ALWAYS_FINISH`, but `service call activity <code>` needs a transaction code that differs
  per Android release.

## Decision

- Stay awake, show view updates and show surface updates are plain shell commands in `:domain`
  (`DeveloperToggleCommands.kt`), run through `AdbTransport` like the existing quick toggles.
- Don't keep activities and the process limit go through a second entry point of ADR 0010's helper
  jar, `DevOptionsMain` (`get`, `process-limit <n>`, `always-finish 0|1`), which calls
  `IActivityManager` by method name via reflection — release-independent, and exactly what
  Settings does. Its line protocol (`DevOptionsHelperCommand`) mirrors the app-info helper's.
  `:application` sees it only as the `ActivityManagerDebugPort`.
- Both helper entry points share one `DeviceHelperDeployment`, so the jar is pushed once per device
  per session.
- Every switch is written only when its readback disagrees with the target, which makes
  SurfaceFlinger's toggling "off" safe, and the shown value always comes from a readback.
- A device that refuses a toggle (surface updates without root) shows "n/a" with the reason; the
  reason is also a toast only when it ends the user's own change.

## Consequences

- Surface updates work on emulators and `adb root` devices only; the UI says so instead of
  pretending.
- The process limit is not persisted by Android: it resets on reboot, as in Settings.
- `always_finish_activities` is the readback for don't keep activities; `setAlwaysFinish` writes it,
  so it stays accurate unless another tool changes only the setting.

## Rejected alternatives

- **`settings put` for don't keep activities** — has no effect until reboot (verified above).
- **`service call activity <code>` for the activity-manager calls** — the codes change between
  Android releases; a per-release table would be fragile and unverifiable here.
