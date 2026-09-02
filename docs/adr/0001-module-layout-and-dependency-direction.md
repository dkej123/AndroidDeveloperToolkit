# 0001 — Module layout and dependency direction

## Status

Accepted

## Context

`AGENTS.md` requires Clean Architecture boundaries: `core`, `application`, and `domain` must not
depend on IntelliJ, Swing, or ToolWindow APIs; Android Studio is a frontend/platform adapter, not
the home for business logic; raw ADB/process execution must never happen in UI code and must be
centralized; core logic should stay KMP-ready. `design/IMPLEMENTATION.md` §1 offers a module tree,
but `tasks/README.md` states that tree is a non-binding suggestion and that this ADR takes
precedence. Later tasks (002–009, 014, and every feature lane) need a fixed module map and a fixed
rule for which module may depend on which, or they will each invent their own boundary.

## Decision

Five Gradle modules, one dependency direction, ports owned by the innermost module that needs them:

```
:domain            pure Kotlin, KMP-ready. Entities, value objects, sealed states/errors, and the
                    inward-facing ports (interfaces) that adapters implement: ProcessExecutor,
                    AdbTransport, ToolLocator, HostNetworkInfo, Clock, DeviceOverridesStore, etc.
                    No coroutines-JVM-only APIs, no IntelliJ/Swing types, no ProcessBuilder,
                    filesystem, or JVM networking types.

:application        pure Kotlin, KMP-ready. Use cases/interactors, MVI reducers, immutable
                    ViewState/Intent/Effect types, feature-local orchestration. Depends only on
                    :domain. Never depends on :adapters-jvm, :adapters-adb, or :intellij.

:adapters-jvm       JVM-only. Implements :domain ports that need the JVM: centralized process
                    execution (ProcessBuilder-backed), filesystem access (capture directory,
                    screenshot/recording pull), host LAN IP discovery. No IntelliJ/Swing types.
                    Depends on :domain only.

:adapters-adb       JVM-only. Implements the :domain `AdbTransport` port: the ddmlib adapter and
                    the binary-adb adapter (ADR 0005), plus tool discovery. Built on top of
                    :adapters-jvm's process executor for the binary path. Depends on :domain and
                    :adapters-jvm only.

:intellij           IntelliJ Platform frontend and composition root. ToolWindowFactory, Swing UI,
                    actions, PersistentStateComponent settings, project services. Wires
                    :application use cases to :adapters-jvm/:adapters-adb implementations via DI
                    at startup (task 007). Depends on :application, :adapters-jvm, :adapters-adb.
                    The only module allowed to import IntelliJ/Swing/ToolWindow APIs.
```

Dependency direction is strictly `:intellij -> :application -> :domain`, with `:adapters-jvm` and
`:adapters-adb` depending downward on `:domain` only and being wired in by `:intellij` at the
composition root — never referenced directly from `:application`. This keeps `:application` able
to run against fakes/adapters for a hypothetical standalone frontend without recompiling against
IntelliJ or a JVM adapter implementation.

Within `:adapters-adb` and any feature area, command construction, execution, and output parsing
are separate types (a builder, an executor call, a parser), never collapsed into one function, per
architecture-guardrails and adb-development.

Feature code (Device, Apps, Display, Network, Logcat, Settings) is organized as feature-local
packages inside `:application` and `:intellij` (e.g. `application/apps`, `intellij/ui/views/apps`)
rather than shared "utils" or a single monolithic ViewModel/manager, per `tasks/README.md`'s
"Parallel feature lanes" requirement that each feature own its commands, parsers, state,
persistence adapter, and UI files.

## Consequences

- A future standalone (non-IntelliJ) frontend can consume `:domain` + `:application` unchanged by
  supplying its own adapters behind the same ports.
- Adapters can be swapped or faked in tests without touching `:application`.
- Five modules is more Gradle wiring up front (task 001) than a single-module plugin, but it makes
  the boundary enforceable by the build graph itself, not just convention.
- `:adapters-jvm` and `:adapters-adb` are two modules, not one, so a future KMP target that wants
  the ADB adapters without full JVM process/filesystem code (or vice versa) is not forced to take
  both.

## Rejected alternatives

- **Single `:plugin` module with internal packages only.** Rejected: nothing stops an accidental
  `application -> intellij` import; the boundary would only be enforced by code review, not the
  build, and architecture-guardrails requires this be structurally enforced where practical.
- **Merge `:domain` and `:application` into one `:core` module.** Rejected: use cases/reducers
  (`:application`) legitimately depend on ports and entities (`:domain`), but collapsing them
  removes the ability to depend on domain contracts (e.g. from `:adapters-adb`) without pulling in
  all use-case orchestration.
- **`:adapters-jvm`/`:adapters-adb` depend on `:application` instead of only `:domain`.** Rejected:
  adapters implement ports, they do not orchestrate use cases; depending on `:application` would
  let adapter code call back into orchestration and blur the boundary.
