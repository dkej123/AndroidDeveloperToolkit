---
name: execute-task
description: User-invoked workflow to implement one task from a task Markdown file end-to-end (read task, TDD implementation, validation, quality gate, one clean commit). Invoke explicitly as /execute-task <path-to-task.md> — not automatic.
---

# Execute Task

Invoke with the path to a task Markdown file: `/execute-task <path>`.

## Workflow

1. Read the entire task file — don't skim, don't start from a partial read.
2. Read relevant dependency tasks and ADRs referenced by (or relevant to) this task.
3. Inspect the existing implementation the task builds on — do not assume prior state.
4. Inspect current git state (`git status`, recent log) before touching anything.
5. Determine the task's Scope, Out of Scope, Acceptance Criteria, and TDD requirements explicitly
   before writing code.
6. Implement using TDD (see the tdd-implementation skill), respecting the architecture-guardrails
   and kmp-ready skills for any core/application/domain/ADB/IntelliJ code touched.
7. Run the relevant (task-scoped) tests.
8. Run broader validation/build (full test suite / project build) to catch regressions.
9. Run quality checks (see the quality-gate skill).
10. Verify every Acceptance Criterion explicitly, one by one.
11. Review the full diff before committing — confirm it matches the task and contains nothing
    unrelated (no stray temp/generated files).
12. Create one clean logical commit for the completed task. Intermediate commits made during the
    work may be created and later squashed/reorganized into that one commit.

## Rules

- Do not start the next task after finishing this one.
- Do not implement anything listed under Out of Scope, even if it seems related or convenient.
- If blocked by a missing architectural prerequisite (e.g. no centralized ADB executor to build
  on), stop and report the problem rather than bypassing the architecture to make progress.

## Report at completion

- What was implemented.
- Tests added (and what behavior they cover).
- Validation performed (commands run, results).
- Final commit hash.
- Any relevant caveats or follow-ups for a future task.
