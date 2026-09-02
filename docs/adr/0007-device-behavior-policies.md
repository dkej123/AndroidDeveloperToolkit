# 0007 — Device behavior policies (labels, Open shell, capture coexistence)

## Status

Accepted

## Context

Task 000's scope requires deciding "application-label resolution, Open-shell platform behavior,
and whether mirroring and screen recording may coexist so later tasks do not invent those
product/adapter rules." `design/IMPLEMENTATION.md` §4 lists `app label   adb -s $S shell dumpsys
package <pkg> | grep -m1 versionName  (label via aapt/PackageManager)` as a note, not a firm
choice, and does not mention "Open shell" in its command table at all even though
`design/README.md` §3 lists **Open shell** as one of the Device view's three basic actions
alongside Reboot and Wake (task 016). `design/README.md` also describes exactly one Mirroring
section and one Capture section in the Device view, each with independent idle/running or
idle/recording states, but does not say explicitly whether both may be active at once.

## Decision

- **Application-label resolution:** resolve labels via **`dumpsys package <pkg>` parsing**
  (`versionName`/`applicationLabel`-style fields already surfaced by that dump), not `aapt`/host
  package-manager introspection. Rationale: `aapt` would require locating and shelling out to a
  *second* SDK build-tool binary (its own discovery/fallback problem, unaddressed anywhere in the
  design or task plan), while `dumpsys package` is already the exact command
  `design/IMPLEMENTATION.md` names, requires no extra tool discovery, and is available through the
  `AdbTransport` port (ADR 0005) task 021 already needs for package discovery. When a label cannot
  be parsed (no match, malformed dump), task 021 falls back to the package name itself as the
  display label — never a blank/crashing row, consistent with adb-development's "handle
  ... malformed/unexpected output without crashing."
- **Open-shell platform behavior:** "Open shell" (task 016) opens an **interactive `adb -s $S
  shell` session in the IDE's own integrated Terminal tool window** (via the Terminal plugin's
  public API to create a new terminal tab pre-seeded with that command), not a bespoke
  plugin-drawn shell UI. This matches `design/IMPLEMENTATION.md`'s general instruction to prefer
  established platform patterns over custom screens for anything the design doesn't fully spec
  visually, and keeps raw interactive shell I/O (which is inherently a raw, unfiltered terminal
  concern) out of the ADB Toolbox tool window's own process-execution and streaming path (ADR
  0005) entirely — task 016 launches the terminal command, it does not implement a shell session
  itself. If the Terminal plugin is unavailable, this is a fixable error toast ("Open shell
  requires the Terminal plugin"), never a silent no-op.
- **Mirroring and screen recording may run concurrently.** `design/README.md`'s Device view has
  independent state machines for the Mirroring section (idle/running) and the Capture section
  (idle/recording), with separate banners, separate Stop controls, and the status bar showing
  *both* a `scrcpy` chip and a `REC 00:42` chip "only while mirroring/recording" (plural
  conditions, not mutually exclusive ones) — nothing in the design disables one while the other is
  active. `adb shell screenrecord` and `scrcpy` are independent OS-level operations against the
  same device and do not conflict at the ADB/device level. Task 017/018 (scrcpy) and task 019/020
  (capture) therefore each own an independent session/process lifecycle (ADR 0004) with no
  cross-feature disable rule between them; the only interaction is that both are individually
  disabled together by the existing "no device online" 45%-opacity rule, like every other
  device-mutating control.

## Consequences

- Task 021 (package discovery) has one label-resolution path to implement and test against
  recorded `dumpsys package` fixtures, with a defined fallback for parse failure.
- Task 016 (device basic actions) does not need to design, implement, or test an interactive PTY
  session, terminal rendering, or bespoke shell UI — it delegates to the platform Terminal.
- Tasks 017–020 do not need to add a mutual-exclusion guard between mirroring and recording; their
  status-bar/badge wiring (task 014) simply reflects each session's own state independently.
- If the Terminal plugin's public API for pre-seeding a command changes across platform versions,
  that risk is isolated to task 016's adapter, not to core device-action logic.

## Rejected alternatives

- **Resolve labels via `aapt dump badging` on the APK.** Rejected: requires locating the APK path
  on-device first (an extra pull/parse step `design/IMPLEMENTATION.md` never specifies) and a
  second SDK binary's discovery/fallback story that would have to be invented from scratch,
  duplicating the adb/scrcpy discovery work ADR 0005/task 004 already does once.
- **Build a bespoke in-tool-window interactive shell (custom terminal emulator/PTY component) for
  Open shell.** Rejected: contradicts `tasks/README.md`'s explicit instruction that secondary/
  under-specified flows use native IntelliJ patterns rather than inventing new UI, and duplicates
  the IDE's own Terminal tool window for no benefit the design calls for.
- **Route "Open shell" through the plugin's own ADB process-execution/streaming path as an
  interactive stream.** Rejected: the centralized `AdbTransport`/process executor (ADR 0005) is
  designed around bounded, cancellable request/response and line-stream calls, not an open-ended
  bidirectional interactive PTY; forcing an interactive shell through it would be exactly the kind
  of "contorting a genuinely JVM/IntelliJ-only concern into a fake shared abstraction" the
  kmp-ready skill warns against.
- **Disallow concurrent mirroring and recording (mutually exclusive).** Rejected: nothing in
  `design/README.md` describes this restriction, the status bar explicitly supports showing both
  chips at once, and there is no technical ADB-level conflict forcing the restriction.
