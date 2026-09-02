# 003 — ADB contracts and command model

## Goal

Define a stable, KMP-ready ADB boundary that feature packages can use concurrently without editing a
single shared command manager.

## Dependencies

- [001 — Project bootstrap](001-project-bootstrap-quality.md)
- [002 — Process execution](002-process-execution.md)
- Required operation/result shapes in `design/IMPLEMENTATION.md` §4 and state/error expectations in
  `design/README.md`; command examples are behavioral references, not permission to bypass typed ports.

## Scope

- Define typed server-scoped and device-scoped requests; device requests require a nonblank serial.
- Define normalized results/errors including transport, stdout/stderr, optional exit status,
  timeout, cancellation, malformed output, and transport limitations.
- Define safe argument and Android remote-shell value primitives; reject unchecked interpolation.
- Define gateway ports for bounded text, streaming text, and binary output plus deterministic fakes.
- Establish feature-local command factories/parsers so Apps, Display, Network, Capture, and Logcat do
  not contend on one monolithic file.

## Out of scope

- ddmlib/binary implementations, tool lookup, concrete feature commands, and UI.

## TDD plan

1. Write failing tests proving serial-less device commands cannot be created and USB/Wi-Fi serials
   remain exact.
2. Add failing escaping/value-validation and result-normalization tests.
3. Implement contracts minimally, then prove fakes cover text, stream, binary, and errors.

## Acceptance criteria

- Shared contracts expose no ddmlib, process, filesystem, IntelliJ, or Swing types.
- Every device operation is serial-scoped by construction.
- Feature commands can be added in feature-local files behind the stable gateway.
- Meaningful contract tests reach at least 80% coverage.

## Validation

- Run domain/application tests, architecture checks, and searches for platform leakage.
