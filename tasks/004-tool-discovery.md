# 004 — adb and scrcpy discovery

## Goal

Resolve configured adb/scrcpy paths and Android SDK platform-tools through isolated JVM/IntelliJ ports.

## Dependencies

- [001 — Project bootstrap](001-project-bootstrap-quality.md)
- [002 — Process execution](002-process-execution.md)
- Discovery order and missing-tool recovery in `design/IMPLEMENTATION.md` §4; persisted paths in
  `design/README.md` State model and Settings entry in the IA prototype.

## Scope

- Define platform-neutral tool identity, version, capability, and actionable discovery errors.
- Implement lookup order selected by ADR: configured path, Android Studio SDK/platform-tools, then
  any explicitly approved PATH fallback.
- Isolate IntelliJ SDK lookup, filesystem checks, environment access, and OS path handling in adapters.
- Validate executable files and query versions asynchronously; cache with explicit invalidation.
- Provide configuration ports for later Settings implementation and deterministic fakes.

## Out of scope

- Downloading tools, implementing Settings UI, starting adb/scrcpy, and visual error presentation.

## TDD plan

1. Write failing lookup-order tests for configured, SDK, PATH, missing, invalid, and stale paths.
2. Add failing macOS/Linux/Windows path and version-output tests using fake filesystem/environment ports.
3. Implement after each expected failure and test cancellation/cache invalidation.

## Acceptance criteria

- Shared code contains no filesystem, environment, IntelliJ SDK, or JVM system APIs.
- Missing tools produce actionable typed errors without blocking the EDT.
- Normal tests use fakes and coverage is at least 80%.

## Validation

- Run focused locator tests, architecture checks, and an opt-in local discovery smoke test.
