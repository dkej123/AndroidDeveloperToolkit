---
name: kmp-ready
description: Keep shared/core/domain/application Kotlin code Kotlin-Multiplatform-compatible where practical. Use automatically whenever writing or editing code in core, domain, or application layers.
---

# KMP-Ready

Apply whenever adding or editing code in `core`, `domain`, or `application` (or any module
intended to be shared logic).

## Rules

- Prefer Kotlin/kotlinx APIs (`kotlinx.coroutines`, `kotlinx.serialization`, `kotlinx.datetime`,
  etc.) over JVM-only or IntelliJ-only equivalents when a KMP-friendly option exists.
- Isolate anything inherently platform-specific — `ProcessBuilder`, filesystem access, networking,
  IntelliJ/Swing types, OS process handling — behind an interface in shared code, implemented by a
  JVM-only adapter.
- Parsing, validation, calculations, use cases, and state transformations should be plain Kotlin
  with no JVM-only or IntelliJ dependency, where that's reasonable to achieve.
- Shared contracts (interfaces, data classes, sealed types) must never expose IntelliJ or Swing
  types in their signatures.
- Don't force KMP compatibility when it would make the architecture worse (e.g. contorting a
  genuinely JVM/IntelliJ-only concern into a fake shared abstraction). JVM-only behavior should
  stay isolated in a JVM-only adapter rather than being awkwardly "shared."
- This is about keeping the door open for a future multiplatform target, not about actually
  building multiplatform targets now.
