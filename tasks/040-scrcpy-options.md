# 040 — scrcpy options

## Goal

Validate, persist, and apply bitrate, maximum size, stay-awake, and show-touches mirroring options.

## Dependencies

- [017 — scrcpy session](017-scrcpy-session.md)
- [018 — scrcpy presentation](018-scrcpy-presentation.md)
- [038 — Settings](038-settings-persistence-ui.md)
- Option semantics/entry point in `design/README.md` §3, scrcpy argument reference in
  `design/IMPLEMENTATION.md` §4, and options SVG variants. No complete options layout is supplied;
  use native IntelliJ form controls with the shared design primitives.

## Scope

- Add KMP-ready option values/defaults/validation and exact structured argument mapping.
- Persist approved ownership and migrate invalid/older state defensively.
- Add a native functional options flow connected to mirroring presentation.
- Ensure applying options affects future starts predictably and never mutates an active session silently.

## Out of scope

- New scrcpy flags, process lifecycle changes, final visual design, and device display overrides.

## TDD plan

1. Write failing boundary/default/migration/argument tests for every option and combination.
2. Write failing UI apply/cancel/active-session/disposal tests, then implement after failures.

## Acceptance criteria

- Generated arguments exactly match typed options and contain no shell concatenation.
- Invalid options do not persist/start; normal tests need no scrcpy and coverage reaches 80%.

## Validation

- Run option/store/argument/platform-flow tests.
