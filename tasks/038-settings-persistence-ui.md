# 038 — Settings persistence and functional UI

## Goal

Persist and edit approved adb/scrcpy paths, capture directory, Logcat buffer size, and project preferences.

## Dependencies

- [004 — Tool discovery](004-tool-discovery.md)
- [007 — IntelliJ lifecycle](007-intellij-composition-lifecycle.md)
- [010 — UI host](010-ui-host-infrastructure.md)
- [013 — Feedback/status](013-feedback-status.md)

## Scope

- Implement ADR-approved application/project state ownership with versioning, defaults, validation, and migration.
- Add a native functional Configurable/settings view and route title/navigation/recovery actions to it.
- Invalidate tool discovery and dependent feature state after accepted changes.
- Keep filesystem and IntelliJ persistence types behind adapters.

## Out of scope

- Wi-Fi pairing, scrcpy option semantics, new settings, secrets, final visual design, and bundled tools.

## TDD plan

1. Write failing state round-trip/default/corrupt/migration/ownership tests.
2. Write failing validation/apply/reset/invalidation and Configurable registration/disposal tests.
3. Implement after failures using fake persistence/filesystem ports.

## Acceptance criteria

- Settings survive at declared scope, invalid paths cannot silently apply, and recovery actions open them.
- Shared code has no filesystem/IntelliJ types; changed logic reaches 80% coverage.

## Validation

- Run settings/store/platform tests and plugin verifier.
