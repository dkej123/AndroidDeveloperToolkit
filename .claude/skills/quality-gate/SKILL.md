---
name: quality-gate
description: Final checklist to run before declaring any task complete — architecture, scope, testing, async safety, and git hygiene. Use automatically before reporting a task/change as done.
---

# Quality Gate

Run before declaring any task complete. Inspect the current task definition, the diff, and the
tests — then run actual validation commands, don't just eyeball it.

## Architecture
- Dependency direction is correct (UI/adapter → application → domain).
- No IntelliJ/Swing types leaked into `core`/`application`/`domain`.
- No `ProcessBuilder`/raw process usage outside the centralized executor.
- No raw ADB calls from UI code.
- No unnecessary giant manager/ViewModel/util classes introduced.

## Scope
- Acceptance criteria are met.
- Nothing under Out of Scope was implemented.
- No unrelated refactoring bundled into the diff.

## Testing
- Meaningful tests exist for the new/changed behavior (not tautological/coverage-only tests).
- Relevant tests actually pass — run them, don't assume.
- Coverage is ≥ 80% where measurable for the change.
- No tests were weakened, skipped, or deleted to hide a failure.

## Async
- EDT is not blocked anywhere in the change.
- Coroutine/process cancellation and disposal are handled.
- Long-running output (e.g. logcat) is bounded in memory.

## Quality
- Errors are surfaced, not silently swallowed.
- No test-specific hacks in production code (e.g. `if (isTest)` branches).
- Naming and module boundaries match the architecture.
- No unnecessary complexity/abstraction relative to what the task needs.

## Git
- Diff matches the task — nothing extraneous.
- No accidental temporary or generated files staged.

## Verdict

Return **PASS** or **FAIL**. On FAIL, fix task-scoped failures before reporting completion —
don't report done with known failures outstanding.
