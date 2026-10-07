# 064 — Layout Inspector, audit, toggles, locale/GPS, MCP settings — UI

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Apply the supplied design for everything above once it is in `design/` (prompt: the inspector/a11y/location/toggles/MCP brief).

## Dependencies

- The design package update; 057–063, 065.

## Scope

- Exactly what the updated `design/README.md` and prototypes specify.

## Out of scope

- Anything the design does not specify.

## TDD plan

- Presentation state tests per the existing view-model patterns; visual goldens; E2E for the inspector flow.

## Acceptance criteria

- Matches the design in both themes and all widths.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
