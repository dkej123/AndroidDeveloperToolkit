---
name: tdd-implementation
description: Test-driven development workflow for any change to production behavior — write a failing test first, verify it fails for the right reason, then implement. Use automatically whenever production code behavior is being added or changed.
---

# TDD Implementation

Apply this workflow whenever production behavior changes — new feature, bug fix, or refactor
with behavioral consequences.

## Workflow

1. Understand the expected behavior from the task/ADR/existing contract.
2. Write a meaningful failing test that encodes that behavior.
3. Run it and confirm it fails for the expected reason (not a compile error or wrong setup).
4. Implement the minimum production code needed to make it pass.
5. Run the test and confirm it passes.
6. Refactor for clarity/architecture fit while keeping tests green.
7. Run the broader relevant test suite (not just the new test) before considering the change done.

## Rules

- Never write production implementation first and back-fill token tests afterward.
- Test behavior and contracts, not implementation details (avoid asserting internal call order,
  private state, etc. unless that's the actual contract under test).
- Do not weaken, skip, or delete a valid test to make the suite pass — fix the code instead, or
  raise the conflict if the test's expectation is actually wrong.
- Normal (fast, CI-run) tests must not require a physical Android device or a real `adb` binary —
  use fake ADB/process executors that implement the same interface.
- Use deterministic coroutine testing (e.g. `runTest`, test dispatchers) — no real delays/timeouts
  in unit tests.
- Use representative recorded ADB output samples for parser tests, not hand-waved minimal strings.
- Maintain at least 80% automated coverage on changed code; do not add tests solely to inflate a
  coverage number without asserting real behavior.
