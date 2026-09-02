# 016 — Device basic actions

## Goal

Implement Reboot, Wake, and Open shell as explicit-serial actions with truthful feedback.

## Dependencies

- [014 — Device-context policies](014-device-context-policies.md)
- [015 — Device facts](015-device-facts-report.md)
- Reboot/Open shell/Wake requirements in `design/README.md` §3 and Device prototype state in
  `design/designs/ADB Toolbox Plugin.dc.html`; ADB/platform mapping in `design/IMPLEMENTATION.md`.

## Scope

- Add feature-local reboot and wake command factories/use cases through the ADB gateway.
- Define a platform-neutral shell-opening intent and IntelliJ terminal/platform adapter per ADR.
- Prevent duplicate in-flight actions, preserve errors/cancellation, and publish feedback.
- Bind minimal functional controls using the shared device policy.

## Out of scope

- Custom terminal emulator, pairing, mirroring/capture, final styling/layout, and destructive confirmations.

## TDD plan

1. Write failing command/use-case tests for exact serial, success, non-zero/unknown status, timeout,
   cancellation, duplicate request, disconnect, and shell-adapter failure.
2. Write failing presenter interaction tests, then implement after failures using fakes.

## Acceptance criteria

- No action executes without an eligible selected serial or blocks EDT.
- UI contains no command construction and shared contracts expose no IntelliJ terminal type.
- Changed logic reaches at least 80% coverage.

## Validation

- Run action/presenter/platform-adapter tests and static architecture checks.
