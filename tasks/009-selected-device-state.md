# 009 — Selected-device orchestration and persistence

## Goal

Own one explicit selected-device context per project and prevent commands against absent/ineligible devices.

## Dependencies

- [008 — Device discovery](008-device-discovery.md)

## Scope

- Implement platform-neutral selection/refresh state transitions over the device repository.
- Never auto-select the first device; restore only an exact persisted serial when present and eligible.
- Model loading, none, online, unauthorized, offline, disconnected/stale, and recoverable error states.
- Define a persistence port and feature-local IntelliJ project-state adapter with schema/version handling.
- Expose a command guard that supplies the eligible serial explicitly or a typed disabled reason.

## Out of scope

- Device picker rendering, Wi-Fi pairing, feature commands, and global UI styling.

## TDD plan

1. Write failing reducer/use-case tests for multiple devices, selection, removal, reconnect, refresh,
   unauthorized/offline rejection, stale responses, and errors.
2. Write failing persistence tests for valid, missing, corrupt, and older state.
3. Implement only after failures; use fake discovery and no real device.

## Acceptance criteria

- No command context can be produced without a currently eligible explicit serial.
- A missing selection is never redirected silently to another device.
- State/persistence tests cover at least 80% and use deterministic coroutines.

## Validation

- Run domain/application/persistence tests and verify no IntelliJ type crosses the persistence port.
