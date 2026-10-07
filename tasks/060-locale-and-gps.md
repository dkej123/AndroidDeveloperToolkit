# 060 — Device language and emulator GPS location

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Change the device locale (incl. RTL and pseudo-locales) and the emulator's GPS fix.

## Dependencies

- Override reconciliation (041) for the locale reset.

## Scope

- Locale read: `cmd locale get-device-locale`, falling back to `getprop persist.sys.locale`; write:
  `cmd locale set-device-locale <tag>`, falling back to `am update-config --locale <tag>` on older builds.
  BCP-47 tag validation; curated list with RTL and `en-XA`/`ar-XB`; the original locale is remembered per
  serial so it can be reset (counts as an override).
- GPS: `adb emu geo fix <lon> <lat>` (note the order), latitude/longitude validation, city presets; refused up
  front on physical devices with a reason.

## Out of scope

- UI (task 064).

## TDD plan

- Command argv and parsers, fallback paths, validation edge cases (ranges, separators, locale tags),
  emulator-only refusal, override bookkeeping.

## Acceptance criteria

- Locale change and reset work on API 33+ and older fallback; GPS sets the fix on an emulator.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
