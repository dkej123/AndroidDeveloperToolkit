# 022 — Apps selection, search, and list presentation

## Goal

Provide searchable package presentation and a persisted selected-package contract shared with Logcat.

## Dependencies

- [010 — UI host](010-ui-host-infrastructure.md)
- [012 — Navigation](012-navigation-routing.md)
- [014 — Device-context policies](014-device-context-policies.md)
- [021 — Package discovery](021-package-discovery.md)
- Apps list/search/selection/empty-state requirements in `design/README.md` §4, shared selection in
  State model/Interactions, and the Apps view in `design/designs/ADB Toolbox Plugin.dc.html`.

## Scope

- Implement case-insensitive label/package filtering, user/system mode, selection, refresh, and empty states.
- Persist selection per project in a feature-local state adapter; revalidate on list/device changes.
- Expose the selected-package application contract consumed by Logcat without UI types.
- Use virtualized platform list/search components and minimal functional rendering.

## Out of scope

- App actions/destructive dialogs and final layout/colors/icons/typography.

## TDD plan

1. Write failing reducer tests for filtering, system toggle, selection, empty query, device switch,
   stale list, restore/corrupt state, and revalidation.
2. Write failing component tests for virtualization, interactions, EDT updates, and disposal.
3. Implement after failures using fake package state.

## Acceptance criteria

- Search matches label and package; selection survives valid restarts and never crosses devices incorrectly.
- Logcat consumes a platform-free selected-package contract.
- Normal tests need no device and changed logic reaches 80% coverage.

## Validation

- Run reducer/persistence/component tests and architecture checks.
