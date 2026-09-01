---
name: architecture-guardrails
description: Enforce Clean Architecture boundaries and centralized process/ADB execution whenever a change touches module structure, dependencies, or crosses layer boundaries (core/application/domain/adapter/UI). Use automatically for any architecture, module, or dependency-related change.
---

# Architecture Guardrails

Apply this whenever code changes affect module boundaries, dependencies between layers, or
process/ADB execution paths.

## Enforce

- Dependency direction: UI/adapter → application → domain. Never the reverse.
- `core`, `application`, and `domain` must contain zero IntelliJ, Swing, or ToolWindow imports.
- No `ProcessBuilder` (or raw OS process APIs) outside a centralized process-execution component.
- Android Studio/IntelliJ code is an adapter that consumes application use cases — it does not
  contain business logic.
- Core must stay reusable by a hypothetical future standalone desktop frontend: no IntelliJ
  Platform assumptions leaking into shared logic.
- Put platform-specific behavior (process execution, filesystem, OS differences) behind
  interfaces defined in `domain`/`application` and implemented by adapters.
- ADB execution is centralized in one component; command construction, parsing, and execution
  are separated where practical (don't collapse them into one god-function/class).
- Keep code feature-local rather than growing shared "utils" grab-bags.
- Contracts between layers (interfaces, DTOs) should be stable — avoid churn that ripples across
  features.
- Avoid introducing abstractions/interfaces that don't have a real second implementation or a
  concrete testing need — don't over-engineer.
- Follow existing ADRs; if a change conflicts with one, flag it instead of overriding silently.
- Structure code so multiple agents/contributors can work on separate features concurrently
  without colliding (feature-local packages/modules, minimal shared mutable state).

## When blocked

If a task requires violating one of these rules to proceed (e.g. no centralized ADB executor
exists yet), stop and report the missing architectural prerequisite rather than bypassing it.
