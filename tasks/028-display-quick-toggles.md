# 028 — Display quick toggles

## Goal

Implement dark-theme, animations-off, and show-touches read/write/readback behavior.

## Dependencies

- [003 — ADB contracts](003-adb-contracts-command-model.md)
- [009 — Selected-device state](009-selected-device-state.md)
- [014 — Device-context policies](014-device-context-policies.md)
- Quick-toggle semantics/states in `design/README.md` §5 and command references in
  `design/IMPLEMENTATION.md` §4.

## Scope

- Add feature-local parsers/commands/use cases for dark theme, show touches, and all three animation scales.
- Model mixed animation values honestly and reconcile partial multi-command failure by final readback.
- Serialize conflicting writes, suppress stale results, and expose loading/applying/error states.

## Out of scope

- Font/density, Display UI, override count, and final styling.

## TDD plan

1. Write failing parser tests for normal, mixed, malformed, unsupported, and permission-denied output.
2. Write failing use-case tests for exact serial, write/readback, partial animation failure, timeout,
   cancellation, rapid toggles, and device switch.
3. Implement after failures with fake ADB.

## Acceptance criteria

- Mixed/partial state is never flattened into false success.
- All commands are serial-scoped, no EDT work occurs, and coverage is at least 80%.

## Validation

- Run toggle parser/use-case tests and architecture checks.
