# 051 — Responsive layout and scaling verification

## Goal

Apply and verify only the responsive breakpoints/scaling behavior supplied by the final design.

## Dependencies

- [050 — Accessibility/keyboard](050-accessibility-keyboard.md)
- Responsive table in `design/README.md`, degradation ladder in
  `design/designs/ADB Toolbox IA.dc.html`, interactive width/theme states in
  `design/designs/ADB Toolbox Plugin.dc.html`, and breakpoints in `design/tokens/tokens.json`.

## Scope

- Implement supplied width-class behavior across every global region and feature view.
- Verify 100%/200% scaling, theme changes, text expansion, wrapping, optional information, scrolling,
  overlay anchoring, and no clipping of required actions at every supplied reference width.
- Add structural breakpoint/scaling tests plus the documented visual comparison matrix.

## Out of scope

- Invented breakpoints, new visual tokens, keyboard/accessibility behavior, and product-command changes.

## TDD plan

1. Write failing component tests for every supplied breakpoint transition and scaling invariant.
2. Implement after failures; use screenshots as additional evidence, not the sole behavioral assertion.

## Acceptance criteria

- Every view remains usable at supplied widths/scales and changes information only at specified breakpoints.
- No unscaled hardcoded dimension remains where the design requires scaling; changed logic reaches 80% coverage.

## Validation

- Run responsive/component tests and the complete width × scale × theme matrix in `runIde`.
