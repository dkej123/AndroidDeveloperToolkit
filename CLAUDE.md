# Android Developer Toolkit — Agent Rules

These rules apply to every session in this repository.

## Process
- Follow existing ADRs; do not contradict or silently bypass them.
- Read the complete implementation task file before starting any work.
- Respect the task's Scope and Out of Scope sections exactly — no unrelated refactoring.
- Use TDD: write a failing test before production code, for every behavior change.
- Minimum automated coverage: 80%. Do not write meaningless/coverage-only tests.
- Inspect the relevant existing code before making assumptions about it.

## Architecture
- Preserve Clean Architecture boundaries between layers.
- `core`, `application`, and `domain` must not depend on IntelliJ, Swing, or ToolWindow APIs.
- Core logic should remain KMP-ready where practical.
- Android Studio is a frontend/platform adapter, not the home for business logic.
- Raw ADB execution must never happen in UI code; ADB/process execution is centralized.
- Never block the IntelliJ EDT.
- Coroutine and process lifecycles must be properly managed (cancellation, disposal).
- Avoid giant managers/ViewModels/utility classes — keep code feature-local and modular.

## Design
- The final visual design system and plugin UI design will be supplied separately. Do not invent
  or copy visual design (including from the `as_plugin` reference repo) ahead of that.

## Reference repository
- `/Users/dkwasniak/Workspace/as_plugin` is READ-ONLY. Use it only as a technical reference for
  Android Studio / IntelliJ plugin configuration (Gradle, plugin.xml, ToolWindow, runIde,
  packaging). Never modify it. Never copy its visual design.
