# 001 — Project bootstrap and quality infrastructure

## Goal

Create a reproducible multi-module plugin build with testing, architecture checks, CI, and an
enforced 80% coverage floor before feature implementation.

## Dependencies

- [000 — Architecture decisions](000-architecture-decisions.md)
- Platform target and plugin metadata essentials in `design/IMPLEMENTATION.md` §§1–2; its suggested
  source tree does not override the ADR-selected Clean Architecture module graph.

## Scope

- Add the Gradle wrapper, pinned Kotlin/JDK/IntelliJ Platform 2.x configuration, repositories, and
  modules selected by the ADR.
- Add minimal valid plugin metadata and `runIde`/plugin-verifier configuration without final UI.
  Plugin-verifier (`verifyPlugin`) must be scoped to a single IDE build target (not the full
  `recommended()` matrix) so it stays fast. Per `CLAUDE.md`, agents must never invoke `verifyPlugin`
  locally/ad hoc — it runs only in CI.
- Configure deterministic coroutine testing, unit-test fixtures, coverage reporting and an 80%
  verification rule for measurable production logic.
- Add architecture checks for dependency direction and forbidden IntelliJ/Swing/process imports.
- Add CI for clean build, tests, architecture checks, coverage, and plugin verification.
- Document local build requirements and keep the read-only `as_plugin` reference untouched.

## Out of scope

- Process/ADB/device behavior, final UI, branding, icons, publishing, signing, or Marketplace upload.

## TDD plan

1. Add failing build/architecture checks for the absent module graph and forbidden dependencies.
2. Confirm the failure is caused by missing configuration, then add the minimum build structure.
3. Add a deliberately violating test fixture to prove the architecture rule fails, then remove it.
4. Run the full clean build and coverage gate.

## Acceptance criteria

- The pinned JDK can run `./gradlew clean build`, tests, coverage, and plugin verification (the
  latter scoped to a single IDE build target; CI runs it, agents never run it locally).
- Dependency direction is frontend/adapters → application → domain.
- Normal tests need no real adb, scrcpy, IDE user profile, or physical device.
- CI and local builds enforce meaningful 80% coverage for changed production logic.

## Validation

- Agents locally: run `./gradlew clean build test koverVerify` (never `verifyPlugin`) or the
  ADR-selected equivalents.
- CI: additionally runs `verifyPlugin`, scoped to a single IDE build target.
- Inspect module dependencies and verify the plugin ZIP contains required runtime modules.
