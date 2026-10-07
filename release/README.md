# Release scripts

Build, test, screenshot and publish ADB Toolbox to JetBrains Marketplace. Runbook for agents:
`.claude/skills/release-plugin/SKILL.md`; listing text and first-upload steps: `docs/marketplace.md`.

| Script | What it does |
|---|---|
| `release.sh <version> [--beta] [--skip-e2e] [--no-screenshots] [--no-publish]` | All of the below in order: bump `pluginVersion`, require the CHANGELOG entry, build, test, screenshots, release commit + push, publish. |
| `build.sh` | `clean :intellij:buildPlugin` → `intellij/build/distributions/adb-toolbox-<version>.zip`; checks the plugin id, version and change notes inside the ZIP. |
| `test.sh [--skip-e2e]` | `clean build koverVerify` (unit/platform tests, architecture, coverage, visual goldens) and the E2E suite in a real Android Studio + emulator with the release ZIP. |
| `screenshots.sh` | `marketplace/screenshots/<version>/*.png` from `MarketplaceScreenshotsE2ETest` (6 scenes, dark/light, 1280×800). |
| `publish.sh [--channel stable\|beta] [--local]` | Preflight, then pushes `v<version>` / `beta-v<version>` (CI publishes) or uploads directly with `$JETBRAINS_MARKETPLACE_TOKEN`. |
| `listing.sh [--version <v>] [--no-screenshots] [--no-description] [--no-urls]` | Updates the Marketplace page: links, description from `plugin.xml`, screenshots of `<version>`. CI: `gh workflow run marketplace-listing.yml`. |

Logs go to `release/.logs/`. Every Gradle step must end with `BUILD SUCCESSFUL` in its log.

## One-time setup

1. Marketplace token: JetBrains account → <https://plugins.jetbrains.com/author/me/tokens> →
   *Generate Token*. The token belongs to your account, so the Golden Diff token works too — but
   GitHub secrets cannot be read back; paste the token again:
   ```bash
   gh secret set JETBRAINS_MARKETPLACE_TOKEN -R dkej123/AndroidDeveloperToolkit
   ```
2. First version: upload `adb-toolbox-<version>.zip` manually at
   <https://plugins.jetbrains.com/plugin/add> (JetBrains requires it for a new plugin ID); fill the
   page from `docs/marketplace.md` and add the screenshots. Later versions use `publish.sh`.

## CI

- `.github/workflows/publish-plugin.yml` — on `v*` (stable) / `beta-v*` (beta) tags or manual
  dispatch: build + tests, then `:intellij:publishPlugin` with the `JETBRAINS_MARKETPLACE_TOKEN` secret.
- `.github/workflows/release.yml` — on `v*`: GitHub release with the ZIP and the CHANGELOG section.
- `.github/workflows/marketplace-listing.yml` — manual: `release/listing.sh` with the token secret.
