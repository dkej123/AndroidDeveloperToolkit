---
name: release-plugin
description: Release runbook for ADB Toolbox on JetBrains Marketplace. Use when asked to cut a release, publish, deploy, ship a new version, bump-and-tag, generate Marketplace screenshots, or prepare the store listing. Covers version bump, mandatory CHANGELOG entry, build, full tests (unit + E2E), Marketplace screenshots, release commit, tag-driven publish and CI monitoring.
---

# Release — ADB Toolbox → JetBrains Marketplace

Plugin ID `com.github.dkwasniak.adbtoolbox`, vendor `dkwasniak`. Scripts live in `release/`
(`release/README.md`), the listing text in `docs/marketplace.md`.

## Hard rules
- **Publishing is irreversible.** Pushing a `v*` / `beta-v*` tag or running `release/publish.sh`
  uploads to JetBrains Marketplace. Only do it when the user explicitly asked for this release in
  this conversation; otherwise stop after step 4 and ask.
- **CHANGELOG entry is mandatory**: `## [<version>] - <YYYY-MM-DD>` with `### Added/Changed/Fixed`.
  The Marketplace change notes (intellij/build.gradle.kts) and the GitHub release notes
  (`.github/workflows/release.yml`) are generated from it; a missing entry fails the build.
- **Stable releases run the full E2E suite** (`release/test.sh` without `--skip-e2e`). Report the
  real results; never claim a gate passed without its log showing `BUILD SUCCESSFUL`.
- Never commit a token. `JETBRAINS_MARKETPLACE_TOKEN` is a repository secret (CI) or a local
  environment variable (`release/publish.sh --local`).
- The very first upload of the plugin ID is manual (JetBrains rule): `release/publish.sh` detects
  it and prints the steps; hand that to the user.

## Steps
1. **Version**: patch for fixes/small UI, minor for new features, `-beta.N` for the beta channel.
2. **CHANGELOG.md**: add the section above the previous release, user-facing wording.
3. **Everything at once** (from the repo root, clean tree):
   ```bash
   release/release.sh <version>            # stable
   release/release.sh <version> --beta     # beta channel
   ```
   It bumps `pluginVersion`, then runs the steps below and stops on the first failure. Use
   `--no-publish` to stop before the tag when the user has not authorized publishing yet.
   Individual steps:
   - `release/build.sh` — `adb-toolbox-<version>.zip`, checks id/version/change notes inside.
   - `release/test.sh` — `clean build koverVerify` (adb hidden from unit tests), then the E2E suite
     in a fresh Android Studio + emulator with that ZIP. Logs: `release/.logs/`.
   - `release/screenshots.sh` — `marketplace/screenshots/<version>/01…06-*.png` (Device, Quick
     toggles, Apps, App details, Logcat, Network; Islands Dark/Light alternating; 1280×800). Look at
     every picture before using it; a stale or broken state on screen means re-running, not shipping.
   - `release/publish.sh [--channel beta] [--local]` — preflight (clean, pushed, on `main` for stable,
     CHANGELOG, tag free, version not yet on Marketplace), then tag push → CI, and follows the
     `Publish Plugin` run with `gh run watch`.
4. **Screenshots on the listing**: when they changed, push them and run the Marketplace Listing
   workflow — see the `marketplace-listing` skill.
5. **After publish**: confirm `Publish Plugin` and `Release` workflows succeeded
   (`gh run list --limit 5`); JetBrains moderates each version before it is visible.

## Environment notes (this crate)
- JDK 21 is found automatically (`release/lib.sh`); the E2E environment is loaded from
  `/work/.e2e-env.sh` when present (`docs/e2e-testing.md`).
- Without KVM two display E2E tests can time out (TCG); see the E2E docs before treating them as
  regressions, and say so in the report.
