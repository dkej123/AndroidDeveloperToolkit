# 010 — UI host infrastructure

## Goal

Provide only the technical Swing/IntelliJ host needed to mount persistent feature views and overlays;
do not create or anticipate the final design system.

## Dependencies

- [007 — IntelliJ lifecycle](007-intellij-composition-lifecycle.md)
- [009 — Selected-device state](009-selected-device-state.md)
- Technical host mapping in `design/IMPLEMENTATION.md` §§2–3 and region/state requirements in
  `design/README.md` Global layout and State model. Visual values are applied later in task 043.

## Scope

- Add a neutral root container with named slots for device context, navigation, active view, feedback,
  and overlays using supported IntelliJ/Swing components.
- Keep feature view instances alive across navigation and expose a feature-view registration seam so
  later agents add feature-local files instead of editing one giant root class.
- Expose actual host width and theme-change events as platform presentation inputs without choosing
  breakpoints, colors, dimensions, fonts, spacing, iconography, or layout styling.
- Tie resize/theme listeners and overlays to `Disposable` lifecycle.

## Out of scope

- Design tokens, colors, typography, branding, icons, painters, final dimensions, breakpoints, visual
  components, or recreation of any prototype. These belong only to task 042 after final design delivery.
- Device/feature behavior and business logic.

## TDD plan

1. Write failing component tests for named slots, view registration, persistent view instances, and
   bounded overlay ownership.
2. Write failing lifecycle tests for width/theme listeners and disposal.
3. Implement the smallest neutral host after failures; run the platform suite.

## Acceptance criteria

- Feature views register without modifying a central switch or sharing mutable UI state.
- Switching views preserves their instances; disposal removes every listener/overlay.
- No custom design token, branded asset, hardcoded visual metric, or business rule is introduced.
- Tests use platform fixtures/fakes and changed logic meets 80% coverage.

## Validation

- Run UI-host tests, architecture checks, plugin verification, and a neutral `runIde` smoke test.
