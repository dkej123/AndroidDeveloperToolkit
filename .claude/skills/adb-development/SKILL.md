---
name: adb-development
description: Rules for any ADB, device, logcat, proxy, package-manager, display, or screen-capture work — centralized execution, multi-device targeting, device-state handling, and testability. Use automatically for any ADB/device-related task.
---

# ADB Development

Apply whenever a task touches ADB, device selection, logcat, proxying, package management,
display, or capture functionality.

## Rules

- Never assume a single connected device — always target an explicitly selected device serial.
- No raw ADB invocation from UI code — go through the centralized ADB abstraction (see the
  architecture-guardrails skill).
- Separate command construction, execution, and output parsing where practical — don't collapse
  them into one function.
- Preserve stdout, stderr, exit code, and cancellation status; don't swallow or merge them.
- Handle unauthorized, offline, and disconnected device states explicitly — don't assume "device
  present" is the only state.
- Handle non-zero exit codes and malformed/unexpected output without crashing the caller.
- Never block the IntelliJ EDT on ADB calls — run them off the UI thread with proper coroutine
  scoping.
- Do not assume `adb` is globally on `PATH` — prefer resolving it via the Android Studio /
  Android SDK `platform-tools` location.
- Support both USB and wireless ADB serials (e.g. `ip:port` forms), not just USB-style serials.
- Account for macOS/Linux/Windows differences in path handling and process execution.
- Long-running commands (e.g. `logcat`) must have explicit cancellation and disposal tied to
  lifecycle, and must bound memory usage (no unbounded buffering of output).

## Testing

- Normal (fast) tests use a fake ADB/process executor implementing the same interface as the
  real one — never a real `adb` binary or physical device. See the tdd-implementation skill.
- Parser tests should use representative recorded ADB output, including edge cases (unauthorized,
  empty, malformed).
