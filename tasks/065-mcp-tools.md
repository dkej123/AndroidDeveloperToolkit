# 065 — MCP tools

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

The tool set agents use, built on the existing use cases (ADR 0015), following Oh My Android's catalog.

## Dependencies

- 057, 058, 059, 060, 062, 063.

## Scope

- See: `screenshot` (PNG scaled to 1 px = 1 dp), `get_ui` (compact text tree with refs, dp bounds),
  `accessibility_audit`.
- Act: `tap` (ref / text / x,y in dp), `swipe`, `type_text` (shell-quoted), `press_key` (back, home, recents, enter…).
- Device: `list_devices`, `get_device_state`, `set_device_settings` (dark mode, font scale, display size, locale,
  orientation, TalkBack, animations, Wi-Fi, airplane, GPS on emulators).
- Apps: `list_apps`, `open_app` (package or deep link), `manage_app` (restart, force-stop, clear data, uninstall —
  destructive annotated), `logcat` (recent lines, package/level filter).
- Data: `read_preferences`, `query_database` (read-only, on a copy).

## Out of scope

- Install APK, emulator snapshots/calls/SMS (later).

## TDD plan

- Each tool against `FakeAdbTransport`: argument validation, output format, access level gating, device selection.

## Acceptance criteria

- An agent can open an app, read its UI, tap a button by ref and read logcat in one session.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
