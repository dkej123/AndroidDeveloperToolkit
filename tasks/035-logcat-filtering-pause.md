# 035 — Logcat filtering, package PID, and pause semantics

## Goal

Provide client-side level/search/package filtering and deterministic unseen-line state while ingestion continues.

## Dependencies

- [021 — Package discovery](021-package-discovery.md)
- [022 — Apps selection/search](022-apps-selection-search.md)
- [034 — Logcat buffer/session](034-logcat-buffer-session.md)

## Scope

- Implement minimum-severity and text-query filtering over immutable entries.
- Resolve selected-package PID(s) through feature-local commands, handle no/multiple/malformed PIDs,
  and re-resolve after app restart without restarting ordinary filters.
- Implement paused/unseen/jump-latest state based on sequences, including eviction while paused.
- Keep filtering cancellable and efficient for 10k+ entries without full copies per line.

## Out of scope

- Swing rendering, toolbar, autoscroll detection, highlighting painter, and device buffer clear.

## TDD plan

1. Write failing pure filter tests for severity/search composition and stable identities.
2. Write failing PID tests for exact serial/package and re-resolution outcomes.
3. Write failing pause/unseen/eviction/performance tests, then implement after failures.

## Acceptance criteria

- Filters are client-side and PID lookup recovers after restarts.
- Pausing never stops ingestion and unseen counts remain honest under eviction.
- Normal tests use fakes and changed logic reaches 80% coverage.

## Validation

- Run filter/PID/pause/performance tests and architecture checks.
