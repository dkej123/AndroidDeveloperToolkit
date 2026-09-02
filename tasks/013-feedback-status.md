# 013 — Feedback, status, and toast infrastructure

## Goal

Create one non-modal feedback channel for command progress, success, failure, fix actions, and status text.

## Dependencies

- [010 — UI host infrastructure](010-ui-host-infrastructure.md)

## Scope

- Define platform-neutral feedback/status models, severity, optional single recovery action, and process indicator.
- Implement a bounded toast queue with deterministic clock/scheduler, success expiry, persistent errors,
  manual dismissal, and message mirroring into status state.
- Add a neutral status and overlay adapter without final styling/layout.
- Dispose timers/subscriptions safely and reject updates after disposal.

## Out of scope

- Feature-specific wording, modal confirmations, design tokens, colors, icons, dimensions, and override logic.

## TDD plan

1. Write failing tests for ordering, queue bound, expiry, persistent errors, fix action, status mirroring,
   process state, and disposal using a fake clock.
2. Implement after each failure; add EDT component-wiring tests.

## Acceptance criteria

- Reversible actions can report through one non-modal channel with no real-delay tests.
- Errors remain actionable and no disposed UI receives timer callbacks.
- Changed logic is at least 80% covered and contains no feature business rules.

## Validation

- Run feedback/state/component tests, coverage, and lifecycle checks.
