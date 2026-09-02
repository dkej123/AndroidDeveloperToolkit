# 029 — Display presentation integration

## Goal

Bind font scale, density, and quick toggles into one functional Display view and shared aggregates.

## Dependencies

- [010 — UI host](010-ui-host-infrastructure.md)
- [012 — Navigation](012-navigation-routing.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Device-context policies](014-device-context-policies.md)
- [026 — Font scale](026-font-scale.md)
- [027 — Density](027-display-density.md)
- [028 — Quick toggles](028-display-quick-toggles.md)
- Display presentation states in `design/README.md` §5 and the Display target in
  `design/designs/ADB Toolbox Plugin.dc.html`; fontScale/density action SVGs.

## Scope

- Add presentation reducers for presets/custom validation, reset, toggles, loading/applying/error, and device changes.
- Bind minimal platform controls, keyboard interactions, feedback, Display badge contribution, and override summary.
- Keep state/action wiring feature-local and cancel subscriptions on disposal.

## Out of scope

- Command/parsing changes, reset-all/reapply, custom visual components, and final design styling/layout.

## TDD plan

1. Write failing reducer tests for every control state, invalid input, readback mismatch, partial failure,
   device state, aggregate contribution, and stale result.
2. Write failing component interaction/EDT/disposal tests, then implement minimally.

## Acceptance criteria

- Presentation always reflects device-truth use-case state and shared aggregates remain serial-scoped.
- UI performs no ADB and changed logic reaches 80% coverage.

## Validation

- Run Display reducer/component tests and architecture/static checks.
