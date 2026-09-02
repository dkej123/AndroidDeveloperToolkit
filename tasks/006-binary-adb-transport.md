# 006 — Binary ADB transport and safe fallback

## Goal

Implement binary adb execution plus a side-effect-safe selector between ddmlib and binary transports.

## Dependencies

- [002 — Process execution](002-process-execution.md)
- [003 — ADB contracts](003-adb-contracts-command-model.md)
- [004 — Tool discovery](004-tool-discovery.md)
- [005 — ddmlib transport](005-ddmlib-transport.md)
- Serial-scoped command families and streaming/binary needs in `design/IMPLEMENTATION.md` §4.

## Scope

- Invoke the resolved adb executable through the central process port only.
- Add exactly one `-s <serial>` for every device request and support server requests separately.
- Support bounded text, streaming, and binary-safe exec-out results.
- Implement the ADR fallback matrix; never retry an ambiguous mutating command after partial execution.
- Preserve transport identity, diagnostics, exit status, timeout, cancellation, and truncation.

## Out of scope

- Concrete feature commands/parsers, device orchestration, and UI.

## TDD plan

1. Write failing tests for command arguments, USB/Wi-Fi serials, all output modes, missing adb,
   non-zero exits, timeout, cancellation, and diagnostics.
2. Write failing selector tests for ddmlib success, unsupported-before-run, safe fallback, and
   ambiguous mutation where fallback is forbidden.
3. Implement only after failures and run the full process/ddmlib suite.

## Acceptance criteria

- Binary adb never bypasses the central process adapter or implicit serial policy.
- Fallback cannot duplicate an uncertain mutation.
- Tests use fakes, preserve all diagnostics, and cover at least 80%.

## Validation

- Run ADB/process tests and static searches for raw adb/process execution outside adapters.
