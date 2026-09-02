# 037 — Logcat controls and presentation state

## Goal

Bind search, severity, package filter, pause, follow/autoscroll, wrap, local clear, counts, and errors.

## Dependencies

- [012 — Navigation](012-navigation-routing.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Device-context policies](014-device-context-policies.md)
- [035 — Logcat filtering/pause](035-logcat-filtering-pause.md)
- [036 — Logcat renderer](036-logcat-virtualized-renderer.md)

## Scope

- Add reducer/persistence for level, query, package-filter enabled, wrap, pause, manual-scroll follow state,
  jump latest, local clear, footer counts, no-device/cleared/filtered/error states, and error badge contribution.
- Bind minimal platform controls and input-safe shortcuts; expected lifecycle stops remain quiet.
- Highlight matched spans through renderer metadata and dispose all actions/subscriptions.

## Out of scope

- Parser/session/filter algorithm changes, device-side clear, export, final visual styling/layout/iconography.

## TDD plan

1. Write failing reducer/store tests for every control/state, combined filters, restore/corrupt state,
   manual scroll, jump, eviction, device switch, expected stop, and error badge.
2. Write failing component/shortcut/EDT/disposal tests, then implement after failures.

## Acceptance criteria

- Clear never invokes `logcat -c`; pause/follow behavior is deterministic and input-safe.
- UI performs no parsing/PID/process work and changed logic reaches 80% coverage.

## Validation

- Run reducer/persistence/component/shortcut tests and 10k+ synthetic UI smoke tests.
