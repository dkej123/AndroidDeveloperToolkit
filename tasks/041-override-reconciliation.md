# 041 — Override reconciliation, reset-all, and reconnect re-apply

## Goal

Coordinate font, density, and proxy overrides per serial without moving feature logic into a global manager.

## Dependencies

- [013 — Feedback/status](013-feedback-status.md)
- [014 — Shared aggregates](014-device-context-policies.md)
- [026 — Font scale](026-font-scale.md)
- [027 — Density](027-display-density.md)
- [029 — Display presentation](029-display-presentation.md)
- [030 — Proxy core](030-proxy-core.md)
- [032 — Network presentation](032-network-presentation-recents.md)

## Scope

- Aggregate observed override contributions per serial and persist only approved re-apply intent/state.
- Implement reset-all by delegating to feature-local reset use cases and reporting per-feature outcomes.
- On reconnect, read device truth first and offer the ADR-approved non-modal re-apply flow; never mutate silently.
- Handle partial failure, cancellation, device switch, stale state, and truthful count reconciliation.

## Out of scope

- Reimplementing feature commands/parsers, modal prompts, and final visual styling.

## TDD plan

1. Write failing aggregate/reset tests for zero/multiple overrides, exact serial, partial failure, cancellation,
   and device switch.
2. Write failing reconnect/read-first/reapply-decline/accept/stale tests.
3. Implement coordinator after failures using fake feature use cases.

## Acceptance criteria

- Counts follow observed device truth; reset/reapply never bypasses feature use cases or crosses serials.
- Partial outcomes are visible, tests need no device, and coverage reaches 80%.

## Validation

- Run coordinator/reconnect/feedback tests and architecture checks for giant-manager regressions.
