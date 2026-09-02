# 0003 — Platform and toolchain baseline

## Status

Accepted

## Context

`design/IMPLEMENTATION.md` states the target is "Android Studio (and IntelliJ IDEA with the
Android plugin), 2024.2+ / New UI," Kotlin, "Gradle IntelliJ Plugin 2.x." Task 001 (project
bootstrap) needs exact, pinned versions to set up the build; every later task needs to know what
language/API level it may assume. `/Users/dkwasniak/Workspace/as_plugin` is a read-only technical
reference (per `AGENTS.md` and the intellij-plugin-development skill) for realistic, already-working
version combinations in this same environment — it uses Kotlin `2.2.20`, the
`org.jetbrains.intellij.platform` Gradle plugin `2.17.0`, JVM target `17`, and Gradle `9.6.1`.

## Decision

- **IDE baseline:** IntelliJ Platform **2024.2** (build `242`) and newer, no upper bound —
  Android Studio Ladybug (2024.2.1) and later, plus IntelliJ IDEA 2024.2+ with the Android plugin,
  matching `design/IMPLEMENTATION.md` exactly. `sinceBuild="242"`, no `untilBuild` pin.
- **JDK:** build and run with **JDK 21** (toolchain), matching the JBR bundled with 2024.2+ IDEs.
  Kotlin/Java bytecode target is **17** for broad compatibility with the 242 platform baseline.
- **Kotlin:** **2.2.x** (matching the version already proven against this platform-plugin version
  in `as_plugin`), language/API level 2.2.
- **Gradle:** **9.6.x** wrapper (matching `as_plugin`'s proven wrapper), invoked via
  `./gradlew`/the wrapper only — never a globally installed Gradle.
- **IntelliJ Platform Gradle Plugin:** **2.17.x** (`org.jetbrains.intellij.platform`), the current
  IntelliJ Platform Gradle Plugin 2.x line referenced by `design/IMPLEMENTATION.md`.
- Exact patch versions are pinned in a Gradle version catalog (`gradle/libs.versions.toml`) in
  task 001, not restated per-module; this ADR fixes the major/minor lines above as the baseline a
  version-catalog bump must stay within unless a future ADR supersedes it.
- `org.jetbrains.android` is declared as an **optional** plugin dependency (per
  `design/IMPLEMENTATION.md` §2): when present, `:adapters-adb`'s ddmlib path is preferred (ADR
  0005); the plugin still functions standalone in plain IntelliJ IDEA via the binary-adb adapter.

## Consequences

- Task 001 can wire the build immediately without guessing versions.
- JDK 21 + bytecode target 17 means the plugin runs on 2024.2+'s bundled JBR while staying source-
  compatible with tooling that still expects 17-level bytecode.
- No support burden for pre-2024.2 IDEs (older Swing APIs, pre-New-UI icon conventions) — consistent
  with `design/IMPLEMENTATION.md` being written against 2024.2+/New UI only.
- Version bumps (e.g. Kotlin 2.3, a new platform-plugin minor) are routine version-catalog PRs, not
  architecture changes, as long as they stay within IDE baseline `242+`.

## Rejected alternatives

- **Lower the baseline to 2024.1 (as `as_plugin` currently targets) for broader compatibility.**
  Rejected: `design/IMPLEMENTATION.md` explicitly specifies 2024.2+/New UI as the design target
  (New UI icon conventions, theme keys); targeting 2024.1 would require supporting an icon/theme
  convention the design was not authored against.
- **JDK 17 toolchain instead of 21.** Rejected: 2024.2+ IDEs bundle JBR 21 and IntelliJ Platform
  Gradle Plugin 2.x's `runIde`/verification tasks are validated against that JBR; building with 17
  risks toolchain mismatches the reference project (already on 21-capable tooling) does not have.
- **Track Kotlin/Gradle "latest" with no explicit pin.** Rejected: unpinned versions break
  reproducible builds and CI (task 001's quality gate) and make a later regression impossible to
  bisect by version.
