# 049 — Apply final design to Settings and secondary flows

## Goal

Apply the delivered design to Settings, Wi-Fi pairing, scrcpy options, and reconnect/reset-all flows.

## Dependencies

- Tasks 038–041 complete.
- [042 — Final design system/assets](042-final-design-system-assets.md)

## Scope

- Apply supplied layouts/states/copy/icons/validation/progress/error treatment to all secondary flows.
- Preserve native IntelliJ Configurable/dialog/popup conventions required by the final platform mapping.

## Out of scope

- Settings/pairing/options/override behavior changes or invented visual flows.

## TDD plan

1. Add failing structural/render tests for each delivered secondary-flow state.
2. Apply the design after failures while retaining validation/secrecy/confirmation tests.

## Acceptance criteria

- Secondary flows match supplied references or documented native deviations.
- Secrets and behavioral guarantees remain unchanged; measurable changes retain 80% coverage.

## Validation

- Run Settings/secondary component/render tests and supplied visual comparisons.
