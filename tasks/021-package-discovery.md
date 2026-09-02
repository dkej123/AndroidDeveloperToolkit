# 021 — Package discovery and metadata

## Goal

Load user/system packages plus label/debuggable metadata for one selected serial through pure parsers and use cases.

## Dependencies

- [003 — ADB contracts](003-adb-contracts-command-model.md)
- [009 — Selected-device state](009-selected-device-state.md)
- Package/metadata requirements in `design/README.md` §4 and command references in
  `design/IMPLEMENTATION.md` §4. The package label note requires the task-000 approved strategy.

## Scope

- Add feature-local commands/parsers for user/all package discovery and approved label/debuggable strategy.
- Use bounded cancellable metadata enrichment rather than an unbounded serial N+1 scan.
- Model stable ordering, package-name label fallback, duplicates, malformed/partial metadata, errors,
  device switches, and stale-result suppression.
- Expose immutable package-list state and a fake repository for downstream Apps/Logcat tests.

## Out of scope

- Search/selection UI, app actions, persistence, and final visual design.

## TDD plan

1. Write failing fixture tests for user/system, LF/CRLF, whitespace, duplicates, malformed and non-ASCII output.
2. Write failing repository tests for exact serial, bounded enrichment, cancellation, fallback, partial
   success, refresh, and stale device changes.
3. Implement only after failures with fake ADB in normal tests.

## Acceptance criteria

- Discovery is serial-scoped, bounded, cancellable, platform-neutral, and resilient to bad metadata.
- No UI/IntelliJ type enters shared models and coverage is at least 80%.

## Validation

- Run package parser/repository tests and architecture/static checks.
