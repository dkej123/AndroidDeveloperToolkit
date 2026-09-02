# 007 — IntelliJ composition root and lifecycle

## Goal

Register the plugin shell and wire platform adapters to application ports without business logic in
the IntelliJ layer.

## Dependencies

- [001 — Project bootstrap](001-project-bootstrap-quality.md)
- [006 — Binary ADB transport](006-binary-adb-transport.md)
- ToolWindow/plugin.xml and component mappings in `design/IMPLEMENTATION.md` §§2–3; use the native
  title actions required there instead of reproducing the prototype's browser title bar.

## Scope

- Register the project ToolWindow and project-scoped services using supported platform APIs.
- Build the composition root for process, ADB, tool discovery, dispatchers, and persistence ports.
- Implement ADR-selected MVVM/MVI state/action seams and project/tool-window coroutine ownership.
- Marshal rendering to EDT while all device/process work stays off EDT.
- Cancel scopes, streams, listeners, popups, and processes through IntelliJ `Disposable` ownership.

## Out of scope

- Device discovery behavior, feature screens, visual layout, styling, icons, and branding.

## TDD plan

1. Write failing platform tests for registration, dependency wiring, background execution, EDT render,
   and disposal exactly once.
2. Implement the minimum composition root and neutral content placeholder.
3. Run plugin verification and lifecycle regression tests.

## Acceptance criteria

- The plugin opens a neutral ToolWindow on the pinned platform.
- No raw ADB/process call or business rule exists in ToolWindow/UI code.
- Disposal leaves no owned work alive; normal tests need no IDE profile or device.
- Changed measurable production logic has at least 80% automated coverage.

## Validation

- Run platform tests, full suite, coverage, plugin verifier, and a `runIde` smoke test.
