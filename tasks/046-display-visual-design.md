# 046 — Apply final design to Display

## Goal

Apply the delivered Display design to font, density, toggles, validation, overrides, and reset states.

## Dependencies

- Tasks 026–029 complete.
- [041 — Override reconciliation](041-override-reconciliation.md)
- [042 — Final design system/assets](042-final-design-system-assets.md)

## Scope

- Apply supplied sections, presets/custom disclosures, validation, readback/applying/error states,
  quick toggles, override/badge/reset treatment, copy, icons, and responsive behavior.

## Out of scope

- Display commands/calculations/state changes or invented visual semantics.

## TDD plan

1. Add failing structural/render tests for each supplied Display state and breakpoint.
2. Apply the design after failures, preserving device-truth reducer tests.

## Acceptance criteria

- Display matches delivered references and never visually claims an unconfirmed value.
- Measurable visual helper changes retain 80% coverage.

## Validation

- Run Display component/render tests and supplied visual matrix.
