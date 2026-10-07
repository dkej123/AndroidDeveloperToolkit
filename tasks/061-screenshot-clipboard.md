# 061 — Screenshots on the clipboard

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Every saved screenshot (normal and full page) is also put on the system clipboard as an image.

## Dependencies

- 019 capture, ADR 0013.

## Scope

- `ClipboardImagePort` in `:domain`, adapter in `:intellij` (IDE clipboard, `Transferable` with `imageFlavor`,
  off the EDT for decoding).
- Capture use case copies after a successful save; a clipboard failure never fails the save, it is reported.
- A setting "Copy screenshots to the clipboard" (default on) — persisted now, its UI per design.

## Out of scope

- Toast copy/actions (design pending).

## TDD plan

- Use case: copies on success only, save still succeeds when copying fails, setting off → no copy.

## Acceptance criteria

- After Screenshot / Full page the image can be pasted into another app.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
