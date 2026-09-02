# 005 — ddmlib ADB transport

## Goal

Implement the Android Studio/ddmlib side of the normalized ADB gateway.

## Dependencies

- [003 — ADB contracts](003-adb-contracts-command-model.md)

## Scope

- Adapt supported ddmlib device discovery and shell execution to normalized gateway results.
- Match devices only by explicit serial and reject unauthorized/offline/disconnected targets.
- Incrementally decode streamed UTF-8 without corrupting split multibyte characters.
- Represent unavailable exit status and other ddmlib limitations honestly.
- Apply timeout/cancellation and detach receivers/listeners on disposal.

## Out of scope

- Binary fallback, feature parsers/commands, device-selection policy, and UI.

## TDD plan

1. Write failing adapter tests for serial lookup, states, output chunks, timeout, cancellation,
   malformed output, exceptions, and missing exit status using a fake ddmlib boundary.
2. Implement one normalized behavior at a time, then test listener/receiver disposal races.

## Acceptance criteria

- No application/domain API exposes `IDevice` or other ddmlib types.
- Unsupported/failed operations never fabricate success or an exit code.
- No tests require Android Studio services or hardware; coverage is at least 80%.

## Validation

- Run adapter tests, architecture checks, and plugin compilation against the pinned platform.
