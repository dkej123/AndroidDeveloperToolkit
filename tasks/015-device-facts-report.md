# 015 — Device facts and copyable report

## Goal

Load and parse device facts for the selected serial and expose a deterministic report/presentation state.

## Dependencies

- [014 — Device-context policies](014-device-context-policies.md)

## Scope

- Add feature-local commands/parsers for Android/API, resolution, effective density, battery/charging,
  ABI, and uptime through the ADB gateway.
- Model loading, partial data, unavailable values, stale serial, cancellation, and error states.
- Expose functional no-device, loading, connected, partial, and recoverable-error presentation states.
- Format a deterministic report containing device identity and available facts.
- Isolate clipboard access behind a platform port and add a minimal functional view binding.

## Out of scope

- Reboot/shell/wake, capture, mirroring, final visual layout/styling, and new facts.

## TDD plan

1. Write failing fixture parser tests for normal, partial, malformed, permission-denied, LF/CRLF, and OEM output.
2. Write failing use-case tests for explicit serial, stale suppression, cancellation, and partial success.
3. Write failing report/clipboard/presenter tests, then implement incrementally with fakes.

## Acceptance criteria

- One malformed fact does not blank the rest and stale-device results never render.
- Shared logic is platform-free, UI performs no ADB, and normal tests use no device.
- Changed logic reaches at least 80% coverage.

## Validation

- Run parser/use-case/report/presenter tests and architecture checks.
