# 057 — UI hierarchy model and uiautomator capture

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

A `:domain` model of the screen's UI hierarchy read from `uiautomator dump`, with dp conversion and hit testing, and an `:application` use case that captures hierarchy + screenshot together. Basis for the Layout Inspector, the accessibility audit (058) and the MCP UI tools (065).

## Dependencies

- ADR 0005 (transport), ADR 0014.
- Existing capture (screenshot bytes) and `wm density` parsing (task 027).

## Scope

- `UiNode` (class, resource-id, text, content-desc, package, pixel bounds, flags: clickable, long-clickable,
  focusable, enabled, checkable, checked, selected, scrollable, password, NAF, children), short class / short id,
  label.
- A hand-written, KMP-ready parser for the `uiautomator dump` XML dialect (attributes, XML entities, nesting,
  `[l,t][r,b]` bounds); malformed input → a typed parse failure, not an exception.
- `UiHierarchy(root, densityDpi)`: `dp(px)`, `px(dp)`, deepest-smallest hit test, node lookup by id.
- `UiAutomatorDumpCommand`: one shell command (dump to `/data/local/tmp`, `cat`, `rm`), retry once after 600 ms
  when the output is empty/short; error text "uiautomator returned nothing. Wait for animations to finish and retry."
- `CaptureLayoutSnapshotUseCase`: hierarchy + effective density + screenshot PNG bytes of the same moment.

## Out of scope

- Any UI (inspector surface follows the pending design).
- Distances/redlines (UI concern).

## TDD plan

- Parser: real dumps (View app, Compose app, nested, entities, empty attributes, malformed bounds, truncated XML).
- dp conversion at 160/420/560 dpi; hit test picks the smallest containing node; zero-size nodes ignored.
- Use case with `FakeAdbTransport`: retry path, density override vs physical, transport failure.

## Acceptance criteria

- Parsing a 2 000-node dump keeps every node with correct bounds and flags.
- dp values match `px * 160 / effectiveDensity`.
- No `javax.xml`/JVM-only API in `:domain`; architecture gate green.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
