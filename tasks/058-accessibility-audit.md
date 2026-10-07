# 058 — Accessibility audit (TalkBack order)

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Approximate TalkBack traversal over a `UiHierarchy` with what each stop announces and its problems, for the inspector's audit mode and the MCP `accessibility_audit` tool.

## Dependencies

- 057.

## Scope

- `AccessibilityTraversal.items(hierarchy)`: depth-first; actionable nodes (clickable, focusable, checkable,
  long-clickable) are one stop and merge descendant text; non-actionable nodes with text are stops; containers
  skipped; siblings ordered by rows (16 dp tolerance) then left-to-right.
- Role inference from class names, Compose fallback from behaviour.
- Spoken text: label, checked state, selected, disabled, role, "double-tap to activate".
- Issues as a typed enum with a message: no label, NAF, image without description, touch target < 48×48 dp
  (ignoring nodes cut by the nearest scrolling viewport).
- Markdown report of the audit.

## Out of scope

- UI.

## TDD plan

- One test per rule and per role mapping; ordering (rows, RTL-free), viewport-cut exemption, password fields.

## Acceptance criteria

- Every issue type is produced and suppressed in the documented cases.
- Report lists stops in order with issues.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
