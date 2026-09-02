# 019 — Screenshot capture

## Goal

Capture one binary-safe PNG for the selected device and save/reveal it through platform ports.

## Dependencies

- [004 — Tool discovery](004-tool-discovery.md)
- [006 — Binary ADB transport](006-binary-adb-transport.md)
- [014 — Device-context policies](014-device-context-policies.md)

## Scope

- Add a feature-local screenshot command using binary output, never UTF-8 text conversion.
- Define platform-neutral capture destination/naming ports and a JVM filesystem adapter.
- Use collision-safe filenames and atomic/cleanup semantics for partial or cancelled files.
- Publish success/error feedback and a Reveal action only after a valid local file exists.
- Bind one minimal functional Device-view action.

## Out of scope

- Screen recording, image editing, custom capture UI, settings persistence, and final visual design.

## TDD plan

1. Write failing tests for exact serial, PNG bytes, empty/malformed output, collision, unwritable path,
   partial cleanup, timeout, cancellation, device switch, and Reveal eligibility.
2. Implement use case then JVM file/reveal adapters after failures; use fake ADB/filesystem in normal tests.

## Acceptance criteria

- PNG bytes are preserved exactly and incomplete files are not reported as success.
- Filesystem/Reveal APIs do not leak into shared code and UI performs no ADB.
- Normal tests use no device/Finder/Explorer and changed logic reaches 80% coverage.

## Validation

- Run capture/file/presenter tests and an optional real-device capture smoke test.
