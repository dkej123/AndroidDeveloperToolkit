# 024 — App clear-data confirmation and action

## Goal

Add the clear-data destructive workflow as one isolated, explicitly confirmed command.

## Dependencies

- [022 — Apps selection/search](022-apps-selection-search.md)
- [023 — App lifecycle actions](023-app-lifecycle-actions.md)
- Clear-data confirmation copy/default-focus requirements in `design/README.md` §4; command reference
  in `design/IMPLEMENTATION.md` §4; clearData SVG variants.
- Inspect the task-024 ADBHelper `pm clear` behavior through
  `.claude/skills/adb-development/references/adbhelper.md`; do not inherit its missing pre-command confirmation.

## Scope

- Add the serial/package-scoped clear-data command/use case and truthful result handling.
- Add a platform confirmation presenter/dialog with Cancel focused/default and Escape cancel.
- Ensure no command runs before explicit destructive confirmation and refresh affected package state after success.

## Out of scope

- Uninstall, other app actions, reusable custom dialog design, and final styling.

## TDD plan

1. Write failing command tests for success/failure/timeout/cancellation/disconnect.
2. Write failing interaction tests for Cancel default, Escape, confirm, duplicate submit, stale package/device,
   and the invariant that cancellation issues zero commands.
3. Implement after failures with fake ADB/dialog ports.

## Acceptance criteria

- Clear data executes only once after explicit confirmation against the exact serial/package.
- The destructive action is never default; tests need no device and coverage is at least 80%.

## Validation

- Run use-case/dialog/presenter tests and a plugin fixture smoke test.
