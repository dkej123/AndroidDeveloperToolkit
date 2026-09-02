# 034 — Logcat bounded buffer and stream session

## Goal

Own exactly one cancellable Logcat stream and a bounded history for the selected serial.

## Dependencies

- [006 — Binary ADB transport](006-binary-adb-transport.md)
- [009 — Selected-device state](009-selected-device-state.md)
- [033 — Logcat parser](033-logcat-parser.md)

## Scope

- Implement a concurrency-safe size-bounded ring buffer with whole-entry eviction, sequence numbers,
  immutable snapshots/deltas, local clear, and defensive entry ceiling.
- Implement idle/starting/running/stopping/stopped/error session lifecycle for
  `adb -s <serial> logcat -v threadtime` through the gateway.
- Handle idempotent start, stderr, start failure, unexpected exit, device switch/disconnect, stop/dispose races,
  bounded publication, and expected cancellation without false errors.

## Out of scope

- Filtering, package PID lookup, pause/autoscroll, rendering, and device-side `logcat -c`.

## TDD plan

1. Write failing buffer property tests for boundaries, eviction, oversize entry, clear, ordering, concurrency.
2. Write failing fake-stream session tests for every lifecycle/race/error and exact serial.
3. Implement after failures and stress with 10k+ entries.

## Acceptance criteria

- Memory/queues remain bounded and no orphan process/coroutine survives lifecycle changes.
- Clear is local only; normal tests need no adb/device and coverage is at least 80%.

## Validation

- Run buffer/session/race/stress tests and static process/ADB checks.
