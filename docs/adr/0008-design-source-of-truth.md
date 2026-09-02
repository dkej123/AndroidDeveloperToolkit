# 0008 — Design source of truth and secondary-flow treatment

## Status

Accepted

## Context

Task 000's scope requires treating `design/README.md`, `design/tokens/tokens.json`, the four
prototypes under `design/designs/`, and `design/icons/` as the final design sources, with
`design/IMPLEMENTATION.md` as the platform mapping, and requires recording that Settings, Wi-Fi
pairing, and scrcpy options have entry points/semantics but no complete bespoke screen layouts, to
be filled in with native IntelliJ patterns rather than invented visual language. `AGENTS.md`
already states the design is final and must not be invented or copied from `as_plugin`; this ADR
exists so later tasks have one explicit routing table instead of re-deciding source priority
per-task, and `tasks/README.md`'s own "Design reference routing" table already encodes most of
this — this ADR adopts it as binding rather than restating a competing version.

## Decision

- **Source-of-truth priority, in order, for any visual or interaction question:**
  1. `design/designs/ADB Toolbox Plugin.dc.html` — the interactive implementation target (exact
     states, layout, copy).
  2. `design/designs/ADB Toolbox Design System.dc.html` — tokens/control specs at the component
     level; `design/tokens/tokens.json` for the machine-readable values feeding it.
  3. `design/designs/ADB Toolbox IA.dc.html` — information architecture, keyboard model,
     degradation ladder, when the Plugin prototype doesn't show a given width/state.
  4. `design/designs/ADB Toolbox Icons.dc.html` + `design/icons/` — icon rules and the production
     SVGs to actually bundle/load.
  5. `design/README.md` — the narrative spec tying all of the above together, and the definitive
     text for copy, tooltips, and the state model when a prototype is ambiguous.
  6. `design/IMPLEMENTATION.md` — platform mapping and command reference **only**; it never
     overrides 1–5 on a visual question, and its module tree is non-binding (superseded by ADR
     0001).
  - `design/designs/support.js` is prototype runtime only, per `AGENTS.md` — never read for
    implementation semantics, never ported or packaged.
  - Feature-specific routing (which section of which source applies to Device/Apps/Display/
    Network/Logcat/Settings) is exactly `tasks/README.md`'s "Design reference routing" table;
    this ADR does not duplicate that table, it binds it as the routing authority for tasks 010–049.
- **Settings, Wi-Fi pairing, and scrcpy options are an explicitly documented visual gap:** the
  handoff specifies their destination, entry points, fields, and semantics (Settings fields in
  `design/README.md`'s "State model" persisted list; Wi-Fi pairing entry point in the device
  picker footer "Pair device over Wi-Fi…"; scrcpy option semantics — bitrate, resolution, stay
  awake — in the Mirroring section's options tooltip) but **not** complete bespoke pixel layouts
  for those three screens. Tasks 000 and 049 (per `tasks/README.md`) resolve this gap the same
  way: **native IntelliJ UI-DSL-v2 forms** (`com.intellij.ui.dsl.builder`, standard `Row`/`Cell`
  layout, standard dialog/settings-page chrome) populated with the supplied tokens (`JBColor`
  mappings from `tokens.json`, `JBUI.Fonts`) and supplied primitives (buttons, fields, chips
  reused from `ui/common/`) for any control that does appear elsewhere in the design system —
  never a new bespoke visual layout invented to fill the gap. Concretely:
  - **Settings** (task 038) is a standard `Configurable`/settings page using UI DSL v2 rows for
    `adbPath`, `scrcpyPath`, `captureDir`, and any other persisted field from ADR 0006's list that
    needs manual editing — laid out as an ordinary IntelliJ settings form, not a custom panel.
  - **Wi-Fi pairing** (task 039) is a standard modal dialog (`DialogWrapper`) with UI-DSL-v2 fields
    for host:port and pairing code, reusing the design system's field/button primitives — not a
    custom-drawn flow.
  - **scrcpy options** (task 040) is a standard popup/settings form (e.g. a `DialogWrapper` or
    popover opened from the Device view's existing 22px options icon button) exposing bitrate,
    resolution, stay-awake, and show-touches as UI-DSL-v2 controls — not a new bespoke panel
    competing with the Device view's own layout.

## Consequences

- Any visual disagreement between sources is resolved by the fixed priority order instead of
  case-by-case debate across tasks.
- Tasks 038–040 and 049 know exactly what "no bespoke layout" means in practice (native UI DSL v2
  + reused primitives), closing the gap task 000's scope calls out by name.
- No task can justify inventing new visual language by pointing at `design/IMPLEMENTATION.md`
  alone — it is explicitly subordinate to the design prototypes/README/tokens/icons on visual
  questions.

## Rejected alternatives

- **Treat `design/IMPLEMENTATION.md` as equally authoritative on visuals since it's the "platform
  mapping."** Rejected: `AGENTS.md` and `tasks/README.md` are explicit that the design in
  `design/` is final and `IMPLEMENTATION.md` is mapping/reference only; treating it as equal would
  let its brief, incomplete command-table notes (e.g. the parenthetical on label resolution)
  silently override the fuller design spec.
- **Design a bespoke Settings/pairing/options screen to "complete" the handoff's visual gap.**
  Rejected: `tasks/README.md` explicitly forbids tasks 000/049 from inventing complete bespoke
  layouts for these three flows; native IntelliJ patterns are the documented resolution.
- **Leave source priority undecided and let each task pick whichever design file is most
  convenient.** Rejected: this is exactly the guessing task 000 exists to prevent — different
  tasks could resolve the same ambiguous case differently (e.g. IA prototype vs. README copy
  disagreeing).
