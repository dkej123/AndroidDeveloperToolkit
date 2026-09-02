# 047 — Apply final design to Network

## Goal

Apply the delivered Network design to proxy editing, validation, active state, host-IP fill, and recents.

## Dependencies

- Tasks 030–032 complete.
- [041 — Override reconciliation](041-override-reconciliation.md)
- [042 — Final design system/assets](042-final-design-system-assets.md)

## Scope

- Apply supplied fields, validation/help, active/off/error/loading states, computer-IP action, recents,
  proxy override treatment, copy, icons, and responsive behavior.

## Out of scope

- Proxy/IP/MRU behavior changes or visual choices absent from the design.

## TDD plan

1. Add failing structural/render tests for each supplied Network state and breakpoint.
2. Apply the design after failures while retaining all validation/readback tests.

## Acceptance criteria

- Network matches delivered references and invalid/off/readback states remain truthful.
- Measurable visual helper changes retain 80% coverage.

## Validation

- Run Network component/render tests and supplied visual comparisons.
