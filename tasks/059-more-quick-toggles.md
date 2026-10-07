# 059 — More quick toggles: rendering, accessibility, connectivity

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Domain commands and the application wiring for the new toggles, behind the existing quick-toggle flow (final readback wins).

## Dependencies

- 028/ADR 0012 toggle infrastructure (`ShellToggleCommand`).

## Scope

- Rendering (debug properties applied live with the activity manager's SYSPROPS transaction):
  show layout bounds (`debug.layout`), GPU overdraw (`debug.hwui.overdraw` = `show`), GPU profile bars
  (`debug.hwui.profile` = `visual_bars`).
- Settings: pointer location (`system pointer_location`), bold text (`secure font_weight_adjustment` 300/0,
  API 31+, "n/a" below), invert colors (`secure accessibility_display_inversion_enabled`).
- Connectivity: airplane mode (`cmd connectivity airplane-mode [enable|disable]`), Wi-Fi (`svc wifi`,
  read `global wifi_on`), mobile data (`svc data`, read `global mobile_data`).
- Rotation: lock portrait / lock landscape / auto (`system accelerometer_rotation`, `user_rotation`) as a
  three-state setting.
- Application layer: read-all and set for the new toggles in the existing quick-toggles controller state.

## Out of scope

- Tiles and layout in the tool window (design pending; task 064).

## TDD plan

- Per command: request argv, parse of every reply form (values, `null`, permission denial, unknown command),
  write sequence; controller tests for read-all and set with readback.

## Acceptance criteria

- Each toggle reads, writes and reports n/a with a reason on refusal.
- Existing toggles unchanged (tests green).

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
