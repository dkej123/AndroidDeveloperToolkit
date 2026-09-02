# 049 — Apply final design to Settings and secondary flows

## Goal

Apply the supplied design system and native IntelliJ patterns to Settings, Wi-Fi pairing, scrcpy
options, and reconnect/reset-all flows whose bespoke layouts are not fully specified.

## Dependencies

- Tasks 038–041 complete.
- [042 — Final design system/assets](042-final-design-system-assets.md)
- Settings entry/fields in `design/designs/ADB Toolbox IA.dc.html`; relevant entry points and state
  in `design/README.md`; settings/pairing/scrcpy notes in `design/IMPLEMENTATION.md`; shared control
  specimens in `design/designs/ADB Toolbox Design System.dc.html`; `design/icons/actions/authorize*.svg`
  and `options*.svg` where applicable.

## Scope

- Reuse supplied tokens, controls, copy, icons, validation/progress/error semantics, and feedback states.
- Use native IntelliJ `Configurable`, dialog, and popup conventions for layout/interaction details the
  handoff does not define; document the mapping instead of designing custom alternatives.

## Out of scope

- Settings/pairing/options/override behavior changes, invented bespoke layouts, or a new visual language.

## TDD plan

1. Add failing structural/render tests for each functional state from tasks 038–041, using supplied
   primitives and the task-000-approved native mapping as the expected presentation.
2. Apply the mapping after failures while retaining validation/secrecy/confirmation tests.

## Acceptance criteria

- Secondary flows use supplied primitives and documented native mappings for every uncovered detail.
- Secrets and behavioral guarantees remain unchanged; measurable changes retain 80% coverage.

## Validation

- Run Settings/secondary component/render tests; compare shared primitives with the Design System
  specimens and review the documented native mapping for details without dedicated references.
