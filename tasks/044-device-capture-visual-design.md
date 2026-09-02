# 044 — Apply final design to Device, mirroring, and capture

## Goal

Apply the delivered Device-area design to facts/actions, scrcpy, screenshot, and recording states.

## Dependencies

- Tasks 015–020 complete.
- [042 — Final design system/assets](042-final-design-system-assets.md)

## Scope

- Apply supplied layouts, sections, loading/empty/content/error/running states, controls, copy, icons,
  process indicators, capture feedback, and responsive behavior to the Device feature.
- Verify every action remains bound to existing presentation intents only.

## Out of scope

- Command/session behavior changes, new capture/mirroring options, or invented visuals.

## TDD plan

1. Add failing structural/render tests for every supplied Device/mirroring/capture state and breakpoint.
2. Apply the design after failures without weakening behavioral tests.

## Acceptance criteria

- All delivered Device/capture states match references and retain serial/lifecycle behavior.
- Visual code contains no ADB/process logic; measurable changes retain 80% coverage.

## Validation

- Run Device/capture component/render tests and the supplied screenshot matrix.
