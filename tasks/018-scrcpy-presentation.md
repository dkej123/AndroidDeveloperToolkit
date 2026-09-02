# 018 — scrcpy presentation and global action

## Goal

Expose mirroring state/actions in the Device feature and global shortcut without adding process logic to UI.

## Dependencies

- [017 — scrcpy session](017-scrcpy-session.md)
- [010 — UI host](010-ui-host-infrastructure.md)
- [012 — Navigation](012-navigation-routing.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Device-context policies](014-device-context-policies.md)
- Mirroring idle/running states, copy, feedback, and shortcut in `design/README.md` §3 and Interactions;
  interactive state target in `design/designs/ADB Toolbox Plugin.dc.html`; mirror/options SVGs.

## Scope

- Map unavailable/idle/starting/running/stopping/error session states to presentation models.
- Bind start/stop and one global action to the same selected-serial intent.
- Route missing-tool recovery to Settings and external exit/errors to feedback.
- Dispose subscriptions and suppress stale sessions after device changes.

## Out of scope

- scrcpy process/argument logic, options editing, capture, and final colors/layout/icons/typography.

## TDD plan

1. Write failing presenter tests for every session state, selected-serial mismatch, missing tool, and error.
2. Write failing action tests for duplicate start, start/stop equivalence, recovery routing, EDT, and disposal.
3. Implement minimally after failures with a fake session.

## Acceptance criteria

- Button/action behavior is serial-correct, nonblocking, and cannot start duplicates.
- UI returns to idle on external exit and has no process logic.
- Changed logic reaches at least 80% coverage.

## Validation

- Run presenter/action/platform tests and plugin verification.
