# 000 — Architecture decisions

## Goal

Record the binding decisions required before any build or production implementation begins.

## Dependencies

None.

## Scope

- Add ADRs for dependency direction and module ownership: `domain`, `application`, JVM adapters,
  ADB adapters, and the IntelliJ frontend.
- Decide the KMP-readiness strategy for shared models, parsing, validation, reducers, and use cases.
- Pin the supported IntelliJ/Android Studio baseline, JDK, Kotlin, Gradle, and IntelliJ Platform plugin.
- Choose MVVM or MVI, immutable UI-state conventions, dispatcher injection, and ownership of
  project/tool-window coroutine scopes and `Disposable` lifecycles.
- Decide ddmlib versus binary ADB capabilities and safe fallback rules, including explicit serials,
  remote-shell escaping, streaming, binary output, timeout, cancellation, and unknown exit status.
- Decide project/application persistence ownership and feature-local state-component policy.
- Decide application-label resolution, Open-shell platform behavior, and whether mirroring and screen
  recording may coexist so later tasks do not invent those product/adapter rules.
- Treat `design/README.md`, `design/tokens/tokens.json`, the four prototypes under `design/designs/`,
  and `design/icons/` as the final design sources; `design/IMPLEMENTATION.md` is the platform mapping.
- Record that Settings, Wi-Fi pairing, and scrcpy options have entry points/semantics but no complete
  bespoke screen layouts in the handoff; use native IntelliJ patterns and supplied primitives without
  inventing a separate visual language.
- Record clean-room use of the unlicensed ADBHelper reference and read-only use of `as_plugin`.

## Out of scope

- Build files, production code, UI implementation, and visual-design decisions.

## TDD plan

Documentation-only task; no production behavior is introduced. Validate ADR links and consistency.

## Acceptance criteria

- Every decision has context, decision, consequences, rejected alternatives, and status.
- Shared layers have no IntelliJ, Swing, ToolWindow, process, filesystem, or JVM-networking types.
- A future standalone frontend can reuse domain/application behavior through platform ports.
- Later tasks do not need to guess state, lifecycle, adapter, or module ownership.
- Feature tasks do not need to guess label/shell/process-coexistence behavior or design-source priority.

## Validation

- Review every ADR against `AGENTS.md` and the five applicable repository skills.
- Verify every visual decision routes to a concrete file under `design/` or an approved native
  IntelliJ treatment for an explicitly uncovered secondary-flow gap.
