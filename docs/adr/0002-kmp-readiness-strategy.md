# 0002 — KMP-readiness strategy

## Status

Accepted

## Context

`AGENTS.md` requires core logic to "remain KMP-ready where practical," and `tasks/README.md`
repeats this as a rule for every implementation task: "Keep domain/application KMP-ready: no
IntelliJ, Swing, ToolWindow, `ProcessBuilder`, filesystem, environment, or JVM networking types.
Platform behavior is behind inward-defined ports." The `kmp-ready` skill adds that this is about
keeping the door open, not building multiplatform targets now, and that KMP compatibility should
never be forced where it would make the architecture worse. Task 000 must pin what "KMP-ready"
concretely means for this project so tasks 002–041 don't each redefine it.

## Decision

- `:domain` and `:application` (ADR 0001) are the KMP-ready surface. They target the JVM now (this
  is not an actual multiplatform build — no `kotlin-multiplatform` plugin is added in task 001),
  but every type and API in these two modules must compile unchanged under `kotlin-multiplatform`'s
  `commonMain` if a target were added later.
- Concretely, in `:domain` and `:application`:
  - Use `kotlinx.coroutines` for concurrency, `kotlinx.datetime` for any timestamps/durations
    crossing the port boundary (e.g. device uptime, recording elapsed time), and
    `kotlinx.serialization` if any domain type needs serialization (persisted state DTOs live in
    `:intellij` per ADR 0006, not here). No `java.time`, `java.io`, `java.net`, or `java.util`
    concurrency types in public signatures.
  - No `ProcessBuilder`, `File`/`Path`, JVM networking (`Socket`, `URL`, `NetworkInterface`), or
    environment/system-property access. Tool discovery, process execution, filesystem, and host
    network info are ports implemented by `:adapters-jvm`/`:adapters-adb` (ADR 0001, ADR 0005).
  - No IntelliJ/Swing/`ToolWindow` types, ever, in `:domain` or `:application` — this is also
    required directly by architecture-guardrails and is CI-checked (task 001).
  - Parsing (device list, logcat lines, `dumpsys`/`getprop` output, package lists), validation
    (font scale range, proxy port range, density bounds), calculations (density from percentage,
    override counts), reducers (MVI state transitions, ADR 0004), and use cases are plain Kotlin
    functions/classes with no JVM-only or IntelliJ dependency — this is achievable for 100% of
    that logic and is not treated as aspirational.
- `:adapters-jvm` and `:adapters-adb` are explicitly JVM-only and are not held to KMP-readiness;
  they exist precisely to isolate what `:domain`/`:application` must not contain (process
  execution, filesystem, host networking, ddmlib). `:intellij` is JVM/IntelliJ-only by definition.
- Task 001's architecture/lint gate enforces this boundary automatically (e.g. a dependency-rule
  check or forbidden-import check on `:domain`/`:application`) rather than relying on review alone.

## Consequences

- Every parser, validator, calculator, and reducer is unit-testable on plain JVM/Kotlin test
  infra with no IntelliJ test fixture, ADB binary, or physical device, satisfying the
  adb-development testing rule and the 80% coverage rule cheaply.
- `:adapters-jvm`/`:adapters-adb` stay free to use the most direct JVM/ddmlib APIs for platform
  logic without inventing a fake abstraction for JVM-only concerns.
- Adding an actual KMP target later (e.g. `iosMain`) is a matter of adding source sets and
  `expect`/`actual` for the ports' JVM adapters — `:domain`/`:application` code itself would not
  need rewriting.

## Rejected alternatives

- **No KMP constraint; write `:domain`/`:application` in ordinary JVM style.** Rejected: directly
  contradicts `AGENTS.md` and `tasks/README.md`'s explicit per-task rule, and would let JVM-only
  types leak in before task 001's gate exists to catch it.
- **Add the `kotlin-multiplatform` Gradle plugin and real additional targets now.** Rejected: the
  kmp-ready skill is explicit that this is about keeping the door open, not building multiplatform
  targets now; there is no current consumer for a second target, so it would be speculative
  infrastructure with no test coverage exercising it.
- **Allow `java.time`/`java.io` in `:domain` since "it's just JVM anyway."** Rejected: it is the
  single largest source of accidental KMP breakage later and is exactly what the per-task rule in
  `tasks/README.md` calls out by name (filesystem, environment, JVM networking).
