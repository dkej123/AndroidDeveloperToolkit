# 017 — scrcpy session lifecycle

## Goal

Implement one cancellable mirroring session per explicit device serial, independent of UI.

## Dependencies

- [002 — Process execution](002-process-execution.md)
- [004 — Tool discovery](004-tool-discovery.md)
- [009 — Selected-device state](009-selected-device-state.md)
- [014 — Device-context policies](014-device-context-policies.md)

## Scope

- Define KMP-ready mirroring options/session state and a JVM scrcpy process adapter.
- Build structured arguments for serial/options without a shell; detect version/capabilities.
- Model idle/starting/running/stopping/exited/error, external window exit, duplicate starts, device switch,
  disconnect, timeout, cancellation, and disposal.
- Publish running-process contribution and actionable missing-tool failure.

## Out of scope

- Mirroring UI, Settings/options UI, screenshot/recording, and final visual design.

## TDD plan

1. Write failing state-machine/argument tests for every state, option, serial, failure, race, and external exit.
2. Confirm failures, implement with fake process/tool ports, then add JVM adapter lifecycle tests.

## Acceptance criteria

- At most one owned session exists per requested context and no orphan process survives disposal.
- State always retains its originating serial and missing scrcpy is actionable.
- Tests need no scrcpy/device and cover at least 80%.

## Validation

- Run session/adapter/process tests and an optional configured scrcpy smoke test.
