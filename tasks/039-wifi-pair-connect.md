# 039 — Wi-Fi pairing and connection

## Goal

Pair/connect a wireless ADB endpoint safely and refresh device discovery without persisting secrets.

## Dependencies

- [008 — Device discovery](008-device-discovery.md)
- [011 — Device picker](011-device-bar-picker.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Device-context policies](014-device-context-policies.md)
- [038 — Settings](038-settings-persistence-ui.md)

## Scope

- Add validated endpoint/pairing-code models and server-scoped pair/connect commands.
- Implement async progress, timeout, cancellation, normalized error, and discovery refresh on success.
- Add a native functional nonblocking flow launched from the device context intent.
- Ensure codes are never persisted or logged and are cleared on completion/disposal.

## Out of scope

- mDNS discovery, QR pairing, USB troubleshooting, final visual design, and storing credentials.

## TDD plan

1. Write failing validation/command/use-case tests for success, invalid input, denied, timeout,
   cancellation, partial pair/connect, and refresh.
2. Write failing secrecy/logging/disposal and platform-flow tests, then implement after failures.

## Acceptance criteria

- Pair/connect never blocks EDT, leaks codes, or targets an implicit device.
- Normal tests use fake ADB and changed logic reaches 80% coverage.

## Validation

- Run pair/connect/flow/secrecy tests and an optional wireless-device smoke test.
