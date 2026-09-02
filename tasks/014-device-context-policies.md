# 014 — Device-context policies and shared aggregates

## Goal

Stabilize shared enablement, badge, running-process, and override-summary contracts before parallel feature work.

## Dependencies

- [009 — Selected-device state](009-selected-device-state.md)
- [012 — Navigation routing](012-navigation-routing.md)
- [013 — Feedback/status](013-feedback-status.md)

## Scope

- Define platform-neutral policy mapping device state to enabled/disabled plus a reason, while keeping
  all controls visible at the presentation-contract level.
- Define feature-local contribution interfaces for badges, running processes, and per-serial overrides.
- Implement immutable aggregation only; feature packages own their state and register contributors.
- Ensure aggregate state changes when the selected serial changes and never mixes devices.

## Out of scope

- Applying/resetting overrides, feature commands, visual opacity/tooltips/colors, or feature UI.

## TDD plan

1. Write failing policy tests for online/loading/none/unauthorized/offline/disconnected states.
2. Write failing aggregate tests for multiple feature contributors, serial switches, removal, and disposal.
3. Implement after failures and prove feature contributors do not share mutable storage.

## Acceptance criteria

- Parallel features can publish badges/process/override state without editing one global manager.
- Aggregates are exact-serial scoped, deterministic, KMP-ready, and at least 80% covered.

## Validation

- Run policy/aggregate tests and dependency/static checks.
