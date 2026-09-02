# 026 — Font-scale behavior

## Goal

Read, validate, apply, read back, and reset font scale for one selected device.

## Dependencies

- [003 — ADB contracts](003-adb-contracts-command-model.md)
- [009 — Selected-device state](009-selected-device-state.md)
- [014 — Device-context policies](014-device-context-policies.md)

## Scope

- Add pure parsing, locale-independent formatting, preset/custom validation, command factory, and use cases.
- Support approved presets, custom range, reset, device-truth readback, stale suppression, and per-serial override contribution.
- Model idle/loading/applying/error without UI types.

## Out of scope

- Density, quick toggles, Display UI, reset-all/reapply, and final styling.

## TDD plan

1. Write failing parser/validation/format tests for boundaries, locale, malformed/empty/permission output.
2. Write failing use-case tests for exact serial, write-readback, reset, mismatch, failure, timeout,
   cancellation, rapid changes, and device switch.
3. Implement after failures with fake ADB.

## Acceptance criteria

- Override state reflects readback, never the requested value alone.
- Shared logic is KMP-ready, tests need no device, and coverage is at least 80%.

## Validation

- Run font parser/calculation/use-case tests and architecture checks.
