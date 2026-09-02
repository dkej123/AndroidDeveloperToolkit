# ADB Toolbox

Android Studio / IntelliJ IDEA plugin providing device, app, display, network, and logcat controls
without leaving the IDE. Architecture is fixed by `docs/adr/` (start at `docs/adr/README.md`); final
visual design lives under `design/` (start at `design/README.md`).

## Build requirements

- **JDK 21** — set `JAVA_HOME` to a JDK 21 install (the toolchain also builds fine from a newer JDK
  that can run Gradle, but the Kotlin/Java toolchain itself is pinned to 21 per
  `docs/adr/0003-platform-and-toolchain-baseline.md`).
- No local Gradle install needed or wanted — always use the wrapper (`./gradlew`), never a globally
  installed `gradle`.
- First build downloads the Gradle 9.6.1 distribution and IntelliJ Platform 2024.2 (build 242)
  artifacts; both require network access and disk space, and can take several minutes.

## Common commands

```shell
./gradlew clean build          # compile + test all modules
./gradlew test                 # unit tests only (no adb/scrcpy/IDE fixture/device required)
./gradlew architectureCheck    # dependency-direction + forbidden-import gate (docs/adr/0001, 0002)
./gradlew koverVerify          # 80% line-coverage floor for :domain/:application/:adapters-jvm/:adapters-adb
./gradlew :intellij:verifyPlugin   # IntelliJ Plugin Verifier CLI against the recommended IDE set
./gradlew :intellij:runIde     # launch a sandboxed IDE instance with the plugin installed
```

`./gradlew check` runs tests, `architectureCheck`, and `koverVerify` together; CI (`.github/workflows/ci.yml`)
runs `check` plus `:intellij:verifyPlugin` on every push and pull request.

## Module layout

Five Gradle modules per `docs/adr/0001-module-layout-and-dependency-direction.md`, dependency
direction `:intellij -> :application -> :domain` (adapters depend downward on `:domain` only and are
wired in by `:intellij` at the composition root):

- `:domain` — pure Kotlin, KMP-ready. Entities, value objects, and inward-facing ports.
- `:application` — pure Kotlin, KMP-ready. Use cases, MVI reducers, ViewState/Intent/Effect types.
- `:adapters-jvm` — JVM-only. Centralized process execution, filesystem, host LAN IP discovery.
- `:adapters-adb` — JVM-only. `AdbTransport` port implementations (ddmlib, binary adb) and tool
  discovery, built on `:adapters-jvm`.
- `:intellij` — IntelliJ Platform frontend and composition root. The only module allowed to import
  IntelliJ/Swing/ToolWindow APIs.

`gradle/scripts/check-architecture.sh` (wired into the `architectureCheck` Gradle task) enforces this
boundary automatically — it fails the build on a forbidden import or a dependency pointing the wrong
direction, rather than relying on review alone.
