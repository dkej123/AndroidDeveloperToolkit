# 043 — Apply final design to global chrome

## Goal

Apply the supplied final layout and visual states to the root host, device bar, navigation, status, feedback, and overlays.

## Dependencies

- [011 — Device bar](011-device-bar-picker.md)
- [012 — Navigation](012-navigation-routing.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Shared policies](014-device-context-policies.md)
- [042 — Final design system/assets](042-final-design-system-assets.md)

## Scope

- Apply supplied global layout, dimensions, responsive rules, component states, icons, and copy.
- Style all device states, chooser, navigation/badges, status/process/override states, toasts, disabled reasons, and overlays.
- Use native tool-window title actions exactly where the final design/platform mapping requires them.

## Out of scope

- Feature-view visual layouts, new behavior, or visual choices absent from the supplied design.

## TDD plan

1. Add failing structural/render/resource tests for each supplied global state and breakpoint.
2. Apply the supplied design after failures and retain functional interaction tests.

## Acceptance criteria

- All global states match delivered references with documented native deviations only.
- No business logic moves into visual components; measurable changes retain 80% coverage.

## Validation

- Run global component/render tests and compare supplied theme/width/scale screenshots in `runIde`.
