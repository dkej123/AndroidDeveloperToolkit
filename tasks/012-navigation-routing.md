# 012 — Feature navigation and view routing

## Goal

Provide persistent routing among Device, Apps, Display, Network, Logcat, and Settings without a shared-file hotspot.

## Dependencies

- [010 — UI host infrastructure](010-ui-host-infrastructure.md)

## Scope

- Define KMP-ready view identifiers, selected-view state, and navigation intents.
- Implement feature registration/routing and per-project restoration of the last view.
- Add keyboard traversal within navigation and stable focus handoff to the active view.
- Reserve feature badge inputs through a small aggregate contract; features provide values locally later.

## Out of scope

- Badge business rules, status/toasts, icons, colors, dimensions, rail styling, or final layout.

## TDD plan

1. Write failing state tests for every destination, restore/default/corrupt state, and unknown registration.
2. Write failing component tests proving navigation does not recreate views and keyboard routing is stable.
3. Implement minimally after failures and test disposal.

## Acceptance criteria

- Later feature agents register local views without editing a monolithic root/router.
- Navigation/restore is deterministic, platform-neutral where practical, and at least 80% covered.

## Validation

- Run routing/persistence/component tests and architecture checks.
