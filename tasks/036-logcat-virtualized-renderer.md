# 036 — Logcat virtualized renderer

## Goal

Render 10k+ Logcat entries with bounded, coalesced EDT updates and stable row identity.

## Dependencies

- [010 — UI host](010-ui-host-infrastructure.md)
- [034 — Logcat buffer/session](034-logcat-buffer-session.md)

## Scope

- Implement a virtualized platform list/editor adapter; never use plain `JTextArea`.
- Apply snapshot/delta batches without replacing/copying the full buffer per append.
- Support stable row identity, variable wrapping input, optional columns, match-span metadata, and eviction.
- Coalesce bursts, cancel scheduled EDT work on disposal, and expose rendering metrics for tests.

## Out of scope

- Final colors/fonts/layout, toolbar/filter behavior, parsing, PID lookup, and session ownership.

## TDD plan

1. Write failing renderer-model tests for append/update/eviction/stable identity/wrap/optional columns.
2. Write failing batching tests proving bounded queued EDT work under bursts and safe disposal.
3. Implement after failures and run a synthetic 10k+ stress test.

## Acceptance criteria

- Rendering remains responsive and bounded without a second unbounded buffer.
- Renderer contains no ADB/parser business logic and changed logic reaches 80% coverage.

## Validation

- Run renderer/batching/stress/disposal tests and inspect EDT/heap behavior with synthetic data.
