# 025 — App uninstall confirmation and action

## Goal

Add the uninstall destructive workflow and reconcile the package list after confirmed success.

## Dependencies

- [024 — Clear data](024-app-clear-data.md)

## Scope

- Add the serial/package-scoped uninstall command/use case and diagnostics.
- Add confirmation with Cancel focused/default, Escape cancel, and no command before confirmation.
- Remove/refresh the package only after confirmed uninstall and clear invalid selection safely.
- Handle already-missing package, partial refresh failure, timeout, cancellation, and device switch.

## Out of scope

- APK install/reinstall, backups, batch uninstall, and final visual design.

## TDD plan

1. Write failing command and state-reconciliation tests for all listed outcomes.
2. Write failing confirmation tests, including zero commands on cancel/stale context.
3. Implement after failures using fake ADB/package repository.

## Acceptance criteria

- Uninstall is one confirmed serial/package operation and never optimistically removes a failed target.
- Selection remains consistent, normal tests need no device, and coverage is at least 80%.

## Validation

- Run uninstall/dialog/repository tests and architecture checks.
