# 042 — Integrate the supplied final design system and assets

## Goal

Translate the repository-owned design tokens and production assets into IntelliJ/Swing primitives
without applying every feature layout in one mixed commit.

## Dependencies

- [010 — UI host](010-ui-host-infrastructure.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Shared policies](014-device-context-policies.md)
- `design/tokens/tokens.json`, `design/designs/ADB Toolbox Design System.dc.html`,
  `design/designs/ADB Toolbox Icons.dc.html`, `design/icons/`, and the Design tokens/Assets sections
  of `design/README.md`.

## Scope

- Map only values supplied by `design/tokens/tokens.json` through `JBColor`, IntelliJ font APIs, and
  `JBUI.scale`, following `design/IMPLEMENTATION.md` §3.
- Import supplied production assets and add resource/package tests for variants and loading.
- Implement only supplied reusable visual primitives with focused APIs; avoid a generic UI utility layer.
- Document native IntelliJ substitutions/deviations with direct design references.

## Out of scope

- Inventing tokens/components/branding, copying `as_plugin`, feature-screen layouts, or behavior changes.

## TDD plan

1. Add failing token/resource/primitive checks derived directly from delivered design artifacts.
2. Implement each supplied mapping after the expected failure; keep existing behavior tests green.

## Acceptance criteria

- Every token/asset/primitive has a supplied source or documented native-platform deviation.
- No product behavior or feature layout changes; measurable helper logic reaches 80% coverage.

## Validation

- Run token/resource/component tests, inspect packaged assets, and compare primitive specimens in both supplied themes/scales.
