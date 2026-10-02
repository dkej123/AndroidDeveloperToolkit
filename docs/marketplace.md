# JetBrains Marketplace

- **Plugin ID:** `com.github.dkwasniak.adbtoolbox` (permanent; decided 2026-10-01)
- **Name:** ADB Toolbox · **Vendor:** dkwasniak (`damian.kwasniak@gmail.com`)
- **Compatibility:** IntelliJ Platform 2024.2+ (build 242+, no upper bound) — Android Studio and
  IntelliJ IDEA; the Android plugin and Terminal are optional dependencies.
- **Status:** not published yet. The first version (1.0.0) must be uploaded manually.

Release mechanics (scripts, CI, token): `release/README.md`. Agent runbook:
`.claude/skills/release-plugin/SKILL.md`.

## Listing

The long description and the "What's new" notes are built into the ZIP (`plugin.xml`
`<description>` and the CHANGELOG section), so the page follows each upload. Fields that live only
on the website:

**Short description (tagline):** Control your Android device from Android Studio — mirroring,
capture, apps, display toggles, proxy and logcat in one tool window.

**Tags:** Android, ADB, Debugging, Tools Integration, Mobile

**Source code:** https://github.com/dkej123/AndroidDeveloperToolkit

**Issue tracker:** https://github.com/dkej123/AndroidDeveloperToolkit/issues

## Screenshots

`release/screenshots.sh` regenerates them for the current version into
`marketplace/screenshots/<version>/`: Android Studio with the release ZIP and an emulator, the
whole 1920×1080 IDE window with a Kotlin file in the editor and the tool window on the right,
Islands Dark (Studio’s default theme). Every view and every App details tab:

| File | Shows |
|---|---|
| `01-device-dark.png` | Device view: mirroring, capture, device facts, font/display scale, quick toggles |
| `02-apps-dark.png` | Apps list with a selected app and its actions |
| `03-app-info-dark.png` | App details → Info |
| `04-app-deep-links-dark.png` | App details → Deep Links (APK analysis) |
| `05-app-permissions-dark.png` | App details → Permissions |
| `06-app-shared-prefs-dark.png` | App details → Shared prefs |
| `07-app-databases-dark.png` | App details → Databases |
| `08-logcat-dark.png` | Logcat stream |
| `09-network-dark.png` | Network view: proxy and throttling |

The capture refuses scenes that still show "Loading…" or a timeout, so a slow device fails the
run instead of producing a broken picture.

Marketplace has no API for screenshots: upload them on the plugin page (Edit → Media) whenever the
UI changed visibly. Check every image first — a leftover dialog, toast or error state means
re-running the script.

## First upload (once)

1. `release/build.sh` → `intellij/build/distributions/adb-toolbox-1.0.0.zip`.
2. `release/screenshots.sh`.
3. <https://plugins.jetbrains.com/plugin/add>: upload the ZIP, choose the license, fill the tagline,
   tags and links above, add the screenshots.
4. Wait for JetBrains' moderation. From then on: `release/release.sh <next version>`.

## Channels

| Channel | Tag | Repository URL for users |
|---|---|---|
| Stable | `v<version>` | default |
| Beta | `beta-v<version>` | `https://plugins.jetbrains.com/plugins/beta/list` (custom repository) |

The tag must match `pluginVersion` in `gradle.properties`; the publish workflow fails otherwise.
