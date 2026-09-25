# 0010 — Application labels and icons via an on-device helper

## Status

Accepted. Supersedes the **Application-label resolution** bullet of ADR 0007; the rest of 0007
stays in effect.

## Context

ADR 0007 chose `dumpsys package <pkg>` parsing for application labels, assuming the dump surfaces
an `applicationLabel`-style field. It does not: on real devices and emulators (verified against
API 28 in the E2E suite) `dumpsys package` prints no label at all, so every Apps row fell back to
the bare package name. The Apps view also calls for an app tile per row (`design/README.md` §4),
and users expect that tile to be the app's own icon — which no `dumpsys`/`pm` text output exposes
either.

A label is a resource reference into the app's APK (often localized) and an icon is a drawable
resource that may be an adaptive or vector XML drawable, so resolving either correctly means
running Android's own resource stack. Only the device has that.

## Decision

- A small Java helper (`adapters-adb/src/deviceHelper`) is run on the device with
  `CLASSPATH=<jar> app_process / dev.acme.adbtoolbox.devicehelper.AppInfoMain <iconPx> [pkg...]`
  as the shell user. Through `PackageManager` it reports each package's localized label, its
  debuggable flag and a launcher icon rendered to a 32px PNG, in a line-based, versioned protocol
  (`AppInfoCommand` in `:domain`) streamed back through the existing `AdbTransport` port (ADR 0005).
- The helper is compiled against compile-only stubs of the handful of `android.*` classes it uses
  and dexed by D8 (`com.android.tools:r8` from Google Maven) during the normal Gradle build, so the
  build needs no Android SDK. The dexed jar ships as an `:adapters-adb` resource.
- It is pushed once per device per IDE session to
  `/data/local/tmp/adb-toolbox-app-info-<content hash>.jar` (skipped when that exact version is
  already there) using `adb push` via `AdbOperation.Host`, which the ddmlib transport reports as
  `Unsupported` and the fallback selector hands to the binary transport.
- One helper run covers a whole refresh (every installed app when more than 40 are unknown, so
  the command line stays short on old devices); records are published to the Apps list in batches
  as they stream in and cached per device like the previous metadata.
- `dumpsys package <pkg>` stays as the fallback for packages the helper did not report or when the
  helper cannot be deployed or started: it still yields the debuggable flag, and the label falls
  back to the package name — still never a blank or crashing row.

## Consequences

- Apps rows show real, localized labels and icons; one helper run replaces a `dumpsys` call per
  package, so enrichment is also faster.
- The plugin writes one small file to `/data/local/tmp` on each device it lists apps for.
- The stubs must match the real platform signatures the helper calls; a mismatch fails at runtime
  on the device (caught by the E2E suite's Apps tests), not at compile time.

## Rejected alternatives

- **Keep `dumpsys package` for labels.** It never yields a label and cannot yield an icon.
- **Pull each APK and parse `resources.arsc`/extract icon files on the host.** Pulls every APK
  (hundreds of MB), duplicates Android's resource resolution, and cannot render adaptive or vector
  icons, which most current apps use.
- **Shell out to `aapt2 dump badging`.** Still needs the APKs on the host plus a second SDK binary
  with its own discovery story (as ADR 0007 already noted), and gives no rendered icon.
- **Check a prebuilt dex into the repository.** An unreviewable binary; building it with D8 from
  source costs one Gradle task.
