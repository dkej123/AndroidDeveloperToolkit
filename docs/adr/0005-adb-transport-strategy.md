# 0005 — ADB transport strategy (ddmlib vs. binary, fallback rules)

## Status

Accepted

## Context

Task 000's scope requires deciding "ddmlib versus binary ADB capabilities and safe fallback
rules, including explicit serials, remote-shell escaping, streaming, binary output, timeout,
cancellation, and unknown exit status." `design/IMPLEMENTATION.md` §2 says: "If the Android plugin
is present, prefer ddmlib / AdbLibService (`AndroidDebugBridge`) over shelling out to `adb`, and
fall back to the binary path from settings when it is not." §4 lists exact commands (including
`exec-out screencap -p`, `logcat -v threadtime`, `screenrecord`) and notes on discovery order
(settings path → `ANDROID_HOME/platform-tools` → `PATH`). adb-development requires: always target
an explicit serial, never invoke raw ADB from UI code, separate construction/execution/parsing,
preserve stdout/stderr/exit code/cancellation, handle unauthorized/offline/disconnected states,
handle non-zero/malformed output without crashing, support USB and wireless (`ip:port`) serials,
account for macOS/Linux/Windows differences, and bound/cancel long-running streams (logcat).

## Decision

- Single `:domain` port, `AdbTransport`, implemented by two `:adapters-adb` adapters selected at
  the `:intellij` composition root (task 007):
  - **`DdmlibAdbTransport`** — used when the Android plugin (`org.jetbrains.android`) is present
    and its `AndroidDebugBridge`/`AdbLibService` is available. Preferred per
    `design/IMPLEMENTATION.md` §2 because it shares the IDE's bridge connection (no duplicate
    `adb` daemon handshake), gives structured device/property listeners, and is what task 005
    normalizes.
  - **`BinaryAdbTransport`** — used when the Android plugin is absent, or ddmlib initialization
    fails, or a capability ddmlib does not expose is needed (below). Shells out to the resolved
    `adb` binary via the centralized process executor (`:adapters-jvm`, task 002). Discovery order
    is exactly `design/IMPLEMENTATION.md`'s: settings path → `ANDROID_HOME/platform-tools` →
    `PATH` (task 004); a missing binary is a fixable error (toast with "Set path…"), never a
    crash or silent no-op.
  - Selection happens once per session at composition (task 007), not per call — a project does
    not flip transports mid-session. If ddmlib initialization throws, the fallback to
    `BinaryAdbTransport` happens once at that point and is logged, not retried per command.
- **Capability rules** for what stays ddmlib vs. what always uses the binary path, regardless of
  which transport is selected as primary:
  - Structured, low-churn calls (device list/state via listeners, shell command with a string
    result, e.g. `getprop`, `dumpsys battery`, `settings put/get`) — ddmlib when selected, else
    binary shell.
  - **Binary output** (`exec-out screencap -p`, pulling a recorded `.mp4`) — ddmlib's
    `syncService`/exec-out equivalent is used when ddmlib is primary; the binary transport always
    reads these as raw bytes (never text-decoded), matching adb-development's "preserve ... don't
    swallow or merge" rule for stdout.
  - **Streaming** (`logcat -v threadtime`) — a long-running, cancellable line stream in both
    transports, bounded by a ring buffer (ADR from task-level design, "buffer 16 MB" in
    `design/README.md`), never fully buffered in memory. Cancellation always tears down the
    underlying process/receiver, never just stops reading.
  - **`scrcpy`** is never routed through ddmlib (ddmlib does not run scrcpy) — always the
    centralized process executor directly, `-s $S` targeted, per `design/IMPLEMENTATION.md` §4.
  - Wireless **pair/connect** (`adb pair`, `adb connect`) always uses the binary transport — ddmlib
    does not perform pairing; this is explicit ADB-CLI behavior task 039 builds on directly.
- **Explicit serials:** `AdbTransport` methods take a `DeviceSerial` value class as a mandatory
  first parameter (never an implicit "current device"); both USB-style and `ip:port` wireless
  serials are valid values, validated by `:domain` (adb-development: "not just USB-style
  serials").
- **Remote-shell escaping:** all shell-argument construction goes through one shared quoting
  function in `:domain` (single-quote wrapping with embedded-quote escaping for the Android shell,
  `sh`-compatible), used by every command builder in `:adapters-adb` — never inline
  string-concatenated shell commands per feature, so escaping bugs are fixed once.
- **Timeout:** every `AdbTransport` call takes an explicit timeout (a short default for
  request/response calls, no default — i.e. caller-supplied lifecycle cancellation only — for
  streams like logcat and mirroring). A timeout is a typed result (`AdbResult.TimedOut`), never a
  thrown exception the caller must guess about.
- **Cancellation:** every call is a `suspend` function honoring structured-concurrency
  cancellation; cancelling the coroutine tears down the underlying ddmlib call or OS process
  (never leaves an orphaned `adb`/`scrcpy` process). This is enforced by the centralized process
  executor (task 002) for the binary path and by cooperative cancellation in the ddmlib adapter.
- **Unknown exit status:** binary-transport results always carry stdout, stderr, and a nullable
  exit code (`null` when the process was killed/cancelled before exit, not coerced to `0`/`-1`);
  ddmlib calls that have no OS exit code map to a domain result that distinguishes
  "succeeded"/"failed with message"/"unknown" rather than forcing a boolean. Callers (use cases)
  must handle the "unknown" case explicitly (e.g. treat as failure-with-warning, per feature), not
  crash or assume success.

## Consequences

- Feature code (tasks 015–040) is written once against `AdbTransport` and works under either
  transport without feature-level branching.
- The capability rules above prevent every feature task from separately deciding "does this go
  through ddmlib," closing exactly the gap task 000 exists to close.
- Two adapters (task 005, task 006) are real, separately testable implementations of one port —
  satisfying architecture-guardrails' rule against introducing an interface with no second
  implementation.
- Binary output and streaming paths cannot silently regress to text decoding because the port
  signature itself distinguishes byte results from line-stream results from string results.

## Rejected alternatives

- **ddmlib only, no binary fallback.** Rejected: `design/IMPLEMENTATION.md` explicitly requires a
  fallback when the Android plugin is absent (plain IntelliJ IDEA), and wireless pair/connect and
  scrcpy have no ddmlib equivalent regardless.
- **Binary `adb` only, no ddmlib.** Rejected: contradicts `design/IMPLEMENTATION.md`'s explicit
  preference for ddmlib when available, and forfeits shared-bridge device listeners the Android
  plugin already maintains.
- **Choose transport per call based on a runtime capability check.** Rejected: per-call branching
  reintroduces the exact feature-level guessing this ADR exists to prevent, and complicates
  reasoning about a single session's process lifecycle; selection is fixed once at composition
  (with the fixed per-capability exceptions above, decided here, not per call).
- **Coerce ddmlib's non-OS-exit-code results into a synthetic `0`/`1`.** Rejected: adb-development
  requires exit status be preserved, not fabricated; a fabricated code would let callers silently
  treat "unknown" as "succeeded."
