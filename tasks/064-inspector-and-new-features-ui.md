# 064 — Layout Inspector, audit, toggles, locale/GPS, MCP settings — UI

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Apply the supplied design (merged 2026-10-07): `design/README.md` §3a Current app, §3b Language & region,
§3c Location, §5.3 extended tiles + rotation, §8 MCP chip, §9 Layout Inspector (editor tab), §10 audit mode,
§11 Settings (clipboard, AI agents); prototypes `ADB Toolbox Plugin`, `Layout Inspector`, `Settings`.
Pending designer corrections are listed in the design follow-up (Display sections stay in the Device view, tiles,
Full page, 28px status bar win over the prototype).

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
