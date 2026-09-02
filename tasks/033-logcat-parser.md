# 033 — Logcat streaming decoder and parser

## Goal

Incrementally decode and parse Logcat threadtime records into platform-neutral entries.

## Dependencies

- [002 — Process execution](002-process-execution.md)
- [003 — ADB contracts](003-adb-contracts-command-model.md)

## Scope

- Define KMP-ready severity, timestamp, PID/TID, tag, message, continuation/raw, and parse-status models.
- Decode split UTF-8, LF/CRLF, final unterminated lines, malformed bytes, and bounded incomplete lines.
- Parse all severities including Assert, stack traces/continuations, daemon headers, OEM variations,
  malformed lines, and non-ASCII fixtures without data loss/crash.

## Out of scope

- Process/session lifecycle, ring buffer, filtering, PID lookup, and UI.

## TDD plan

1. Write failing byte-boundary tests for multibyte splits, malformed bytes, huge incomplete lines, and EOF.
2. Write failing fixture tests for every record/continuation/malformed case.
3. Implement decoder then parser only after expected failures.

## Acceptance criteria

- Parsing is deterministic, tolerant, KMP-ready, and preserves useful malformed input.
- Tests use checked-in sanitized fixtures and cover at least 80%.

## Validation

- Run decoder/parser/property tests and architecture checks.
