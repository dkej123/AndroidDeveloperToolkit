# 020 — Screen recording

## Goal

Record, stop, pull, clean up, save, and reveal one screen recording with truthful session state.

## Dependencies

- [019 — Screenshot capture](019-screenshot-capture.md)

## Scope

- Add feature-local remote recording, graceful stop, pull, remote cleanup, and local-save workflow.
- Model idle/starting/recording/stopping/pulling/saved/error plus ADB maximum-duration completion.
- Use a monotonic clock for elapsed state and no real-delay tests.
- Handle partial failures explicitly, keep session ownership by serial, and clean up safe remnants.
- Bind minimal functional start/stop controls and running-process/status feedback.

## Out of scope

- Screenshot logic changes, video editing, custom player, Settings UI, and final visual design.

## TDD plan

1. Write failing state-machine tests for success, auto-complete, start/stop/pull/cleanup failure,
   cancellation, disconnect, device switch, duplicate start, clock boundaries, and disposal.
2. Implement each transition after its expected failure using fake ADB/clock/filesystem ports.

## Acceptance criteria

- Reveal appears only for a confirmed local MP4; partial results are truthful and recoverable.
- No orphan process/timer/remote work survives disposal and EDT is never blocked.
- Normal tests need no device/real time and changed logic reaches 80% coverage.

## Validation

- Run recording state-machine/presenter tests and an optional real-device smoke test.
