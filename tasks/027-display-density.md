# 027 — Display-density behavior

## Goal

Read physical/effective density, calculate presets, apply/read back custom DPI, and reset safely.

## Dependencies

- [003 — ADB contracts](003-adb-contracts-command-model.md)
- [009 — Selected-device state](009-selected-device-state.md)
- [014 — Device-context policies](014-device-context-policies.md)

## Scope

- Add pure `wm density` parsing for physical/current values and representative OEM variations.
- Add deterministic percentage-to-DPI rounding, custom validation/safety policy, command factory, and use cases.
- Read back every mutation and publish a per-serial override contribution.
- Handle malformed/unsupported output, rapid changes, cancellation, and stale serials.

## Out of scope

- Resolution/orientation, font scale, Display UI, reset-all/reapply, and final styling.

## TDD plan

1. Write failing fixtures/calculation tests for default/override, rounding, ranges, CRLF, malformed and denied output.
2. Write failing use-case tests for exact serial, apply/readback, reset, mismatch, failure, timeout,
   cancellation, and device switching.
3. Implement after failures with fake ADB.

## Acceptance criteria

- UI consumers receive physical and observed effective density with truthful override state.
- Tests need no device, shared code is platform-free, and coverage is at least 80%.

## Validation

- Run density parser/calculation/use-case tests and architecture checks.
