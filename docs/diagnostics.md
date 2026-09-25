# Diagnostics and bug reports

ADB Toolbox writes a diagnostics log while it runs and can package everything needed to debug a
problem into one ZIP.

## Collect a bundle

Any of: **Help → ADB Toolbox Diagnostics → Collect Diagnostics…**, the ⋮ menu of the ADB Toolbox
tool window, or **Settings → Tools → ADB Toolbox → Collect Diagnostics…**.

The ZIP is written to `~/Desktop` (or the log folder) as `adb-toolbox-diagnostics-<time>.zip` and
contains:

| Entry | Content |
|---|---|
| `environment.txt` | plugin, IDE, OS, JDK, Android/Terminal plugin, `PATH`/`ANDROID_HOME` (IDE process vs login shell) |
| `adb.txt` | fresh adb/scrcpy discovery, live `adb version` and `adb devices -l`, ddmlib bridge state, plugin device state, settings |
| `threads.txt`, `jvm.txt` | thread dump and heap/GC/thread stats at collection time |
| `adb-toolbox/` | `adb-toolbox.log` (+ rotated files) and recent `perf-*.txt` recordings |
| `ide/` | last 2 MB of `idea.log` and the IDE's three newest `threadDumps-freeze-*` folders |

Nothing is redacted: paths, IP addresses and device serials are included as-is.

## Performance problems

Reproduce the slowness and run **Record Performance (60 s)** while it happens. It samples every
thread every 200 ms and writes `perf-<time>.txt` with the hottest frames on the EDT (UI thread,
every state) and across all running threads. Then collect a bundle — the recording is included.

The log also records, without any action:
- `[ui.edt] slow EDT task` — plugin code that held the UI thread ≥ 50 ms, with the resumed coroutine;
- `[logcat] slow drain` / `throughput` — Logcat rendering cost and volume;
- `[perf] jvm` and `[perf] commands in last 60s` — memory/GC and per-command counts and timings.

## The log

`<IDE log dir>/adb-toolbox/adb-toolbox.log` (**Open Diagnostics Log Folder**), 5 MB × 5 files.
One line per event, e.g.

```
2026-09-24T11:18:03.412Z WARN  [process] finished command="adb -s R58N shell wm density" outcome=Completed(exitCode=1) ms=142 stderrTail=... thread=...
```

Categories: `process` (every external command), `adb` (transport used, fallbacks, outcomes),
`discovery`, `device`, `feedback` (every toast shown), `ui.edt`, `logcat`, `perf`, `lifecycle`.
WARN/ERROR lines are mirrored to `idea.log`. **Verbose diagnostics** in Settings adds DEBUG lines
(every successful command and drain) — enable only when asked.
