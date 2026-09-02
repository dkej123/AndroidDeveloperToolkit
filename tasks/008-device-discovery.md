# 008 — Device discovery and parsing

## Goal

Observe and parse all ADB-visible devices, including online, unauthorized, offline, and malformed cases.

## Dependencies

- [005 — ddmlib transport](005-ddmlib-transport.md)
- [006 — Binary ADB transport](006-binary-adb-transport.md)
- [007 — IntelliJ lifecycle](007-intellij-composition-lifecycle.md)

## Scope

- Define KMP-ready device identity/state/connection models keyed by exact serial.
- Add pure `adb devices -l` parsing with fixtures for USB, Wi-Fi, multiple devices, daemon chatter,
  CRLF, missing fields, duplicates, unknown states, and malformed lines.
- Adapt ddmlib hotplug/state events and binary refresh into one observable repository.
- Coalesce noisy events deterministically and cancel listeners/refreshes on disposal.

## Out of scope

- Selection, persistence, pairing, device picker UI, and device commands.

## TDD plan

1. Write failing fixture parser tests for every listed state and malformed input.
2. Write failing repository tests for initial load, refresh, arrival/removal, errors, coalescing, and disposal.
3. Implement after failures using fake gateways and deterministic dispatchers.

## Acceptance criteria

- All devices retain stable explicit serials and truthful state.
- Discovery never blocks EDT, leaks listeners, crashes on malformed output, or needs hardware in CI.
- Changed logic has at least 80% coverage.

## Validation

- Run parser/repository tests, architecture checks, and an optional real-adb smoke test.
