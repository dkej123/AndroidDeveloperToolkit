# 048 — Apply final design to Logcat

## Goal

Apply the delivered Logcat design to toolbar, filters, virtualized rows, paused/follow state, footer, and empty/error states.

## Dependencies

- Tasks 033–037 complete.
- [042 — Final design system/assets](042-final-design-system-assets.md)

## Scope

- Apply supplied severity/search-hit tokens, row/column metrics, responsive columns, wrapping, controls,
  paused/unseen overlay, counts, empty states, error badge, copy, and icons.
- Preserve bounded/coalesced rendering and input-safe shortcuts.

## Out of scope

- Parser/filter/session behavior changes, device buffer clear, or invented visuals.

## TDD plan

1. Add failing renderer/structural tests for every supplied severity/state/breakpoint.
2. Apply the design after failures and rerun 10k+ performance tests.

## Acceptance criteria

- Logcat matches delivered references without regressing virtualization, memory, or EDT behavior.
- Measurable visual helper changes retain 80% coverage.

## Validation

- Run Logcat render/performance tests and supplied theme/width/scale comparisons.
