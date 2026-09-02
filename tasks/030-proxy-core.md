# 030 — Global HTTP proxy behavior

## Goal

Validate, read, enable, read back, and reset the selected device's global HTTP proxy.

## Dependencies

- [003 — ADB contracts](003-adb-contracts-command-model.md)
- [009 — Selected-device state](009-selected-device-state.md)
- [014 — Device-context policies](014-device-context-policies.md)

## Scope

- Add KMP-ready host/port value objects, normalization, validation, safe command factory, and parser.
- Handle disabled forms, active endpoints, malformed/OEM/permission output, and ports 1–65535.
- Read on selection/reconnect and read back after enable/reset; publish per-serial override contribution.
- Serialize writes and suppress stale device results.

## Out of scope

- Host IP discovery, recents/UI, authentication/PAC/VPN/certificates, reset-all/reapply, and final styling.

## TDD plan

1. Write failing validation/parser fixture tests for boundaries, unsafe input, off/active/malformed/CRLF output.
2. Write failing use-case tests for exact serial, enable/readback, reset/readback, mismatch, failure,
   timeout, cancellation, rapid apply, and device switch.
3. Implement after failures using fake ADB.

## Acceptance criteria

- Invalid endpoints issue zero commands and active state reflects readback only.
- Shared code is platform-free, tests need no device, and coverage is at least 80%.

## Validation

- Run proxy validation/parser/use-case tests and architecture checks.
