# 0014 — Layout inspection from the uiautomator hierarchy, reusing Oh My Android (MIT)

## Status

Accepted (2026-10-07). ADR 0005's transport rules and ADR 0009's reference rules are unchanged;
this ADR adds a second, licensed reference with different rules.

## Context

The user asked for a Layout Inspector (sizes and distances in dp, grid, overlay), an accessibility
audit, emulator GPS location, device language, more quick toggles, screenshots on the clipboard and
an MCP server for coding agents, explicitly reusing the open-source macOS app
[Oh My Android](https://github.com/ateymoori/oh-my-android) (MIT, © 2026 Royan AB), which has all of
them. Unlike ADBHelper (ADR 0009, no license, clean-room only), MIT allows copying with attribution.

Android Studio's own Layout Inspector needs a debuggable app and an agent inside its process.
`uiautomator dump` needs neither: it serialises the accessibility node tree of the whole screen
(Views and Compose semantics alike, any app, release builds) as XML with pixel bounds and the
accessibility flags. It refuses while the screen animates and cannot see `FLAG_SECURE` content
beyond what accessibility exposes. That is the data Oh My Android's inspector and audit use, and
what Google's `android layout` CLI exposes too.

## Decision

- **Reuse:** logic from Oh My Android may be ported (translated from Swift to Kotlin) into this
  repository. Every ported file says so in its KDoc ("Ported from Oh My Android, MIT — <source
  file>"), and `THIRD_PARTY_NOTICES.md` carries its copyright and license text, shipped in the
  plugin ZIP. Its **visual design is not reused**: UI follows `design/` (ADR 0008).
- **Data source:** the inspector and the audit read `uiautomator dump` (written to
  `/data/local/tmp`, read back and deleted in one shell command, one retry after 600 ms when it
  returns nothing) plus the effective density from `wm density`, with a screenshot taken in the same
  capture. No app instrumentation, no debuggable requirement.
- **Model in `:domain`** (KMP-ready, so the XML is parsed by a small hand-written parser for the
  flat `uiautomator` dialect, not `javax.xml`): `UiNode` tree with pixel bounds and flags,
  `UiHierarchy` with density and dp conversion, hit testing (deepest smallest node) and the
  accessibility traversal/audit. Geometry stays in pixels; dp is derived.
- **Foreground app** is read on demand from `dumpsys activity activities` (first
  `Resumed:`/`ResumedActivity:` line), never polled by the domain; callers decide when.
- **New device settings** follow the existing patterns: on/off settings are `ShellToggleCommand`s
  (read + writes + parser, final readback wins); emulator-only ones use the emulator console
  (`adb emu …`) like network throttling and are refused on physical devices up front.

## Consequences

- Inspector and audit work for every app and both UI toolkits, but show only what accessibility
  exposes (no padding/margin attributes, no Compose parameters); "distances" are between node
  bounds. Sizes are converted with the *effective* density, so they follow display-size overrides.
- A screen that keeps animating can make the dump fail; the error says to wait and retry.
- `THIRD_PARTY_NOTICES.md` must be updated whenever more of Oh My Android is ported.
