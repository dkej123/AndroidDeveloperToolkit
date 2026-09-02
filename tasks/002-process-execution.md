# 002 — Central process execution

## Goal

Provide the only raw JVM process boundary, supporting bounded text, streaming text, and binary output.

## Dependencies

- [001 — Project bootstrap](001-project-bootstrap-quality.md)
- Streaming/binary/lifecycle consumers in `design/IMPLEMENTATION.md` §§3–4: scrcpy, screenshot,
  recording, and Logcat. These define required capabilities, not platform types in shared contracts.

## Scope

- Define KMP-ready request/result/stream contracts without JVM or IntelliJ types.
- Implement structured executable/argument invocation in the JVM adapter; never concatenate a local shell.
- Preserve stdout, stderr, exit status, start failure, truncation, timeout, and cancellation.
- Decode UTF-8 incrementally across chunks, apply bounded backpressure, and support a binary-safe sink.
- Terminate owned process trees on timeout, cancellation, or disposal and complete exactly once.
- Provide deterministic fakes and a controlled helper process for integration tests.

## Out of scope

- ADB commands, tool discovery, interactive terminal emulation, and Logcat-specific buffering.

## TDD plan

1. Write failing contract tests for success, non-zero exit, split UTF-8, binary bytes, truncation,
   backpressure, start failure, timeout, cancellation, and dispose races.
2. Confirm each failure, then implement one behavior at a time in the JVM adapter.
3. Add cross-platform argument/path tests and run broader architecture tests.

## Acceptance criteria

- Raw process APIs exist only in the process-JVM adapter.
- Text and binary output are never conflated; memory and queues remain bounded.
- Cancellation/disposal leaves no process, reader thread, or coroutine running.
- Tests are deterministic and require neither adb nor a device; coverage is at least 80%.

## Validation

- Run focused process tests, the full suite, coverage, and static searches for raw process APIs.
