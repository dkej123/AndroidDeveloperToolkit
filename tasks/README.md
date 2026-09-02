# ADB Toolbox implementation backlog

Each numbered file is one independently reviewable task and must produce one logical commit. Read
`AGENTS.md`, this master plan, the entire selected task, its dependencies, and applicable ADRs/skills
before implementation. Do not start a dependent task early or bundle the next task into the same commit.

## Sources and constraints

- Repository rules: `AGENTS.md` (symlink to `CLAUDE.md`).
- Final product, interaction, copy, state, and visual specification: [`design/README.md`](../design/README.md).
- IntelliJ component mapping, command reference, and Definition of Done:
  [`design/IMPLEMENTATION.md`](../design/IMPLEMENTATION.md). Its module tree is a non-binding
  suggestion; repository ADRs and Clean Architecture rules take precedence.
- Interactive implementation target: [`design/designs/ADB Toolbox Plugin.dc.html`](../design/designs/ADB%20Toolbox%20Plugin.dc.html).
- Design-system specimen: [`design/designs/ADB Toolbox Design System.dc.html`](../design/designs/ADB%20Toolbox%20Design%20System.dc.html).
- Information architecture, keyboard model, and degradation ladder:
  [`design/designs/ADB Toolbox IA.dc.html`](../design/designs/ADB%20Toolbox%20IA.dc.html).
- Icon rules/specimens: [`design/designs/ADB Toolbox Icons.dc.html`](../design/designs/ADB%20Toolbox%20Icons.dc.html).
- Machine-readable values: [`design/tokens/tokens.json`](../design/tokens/tokens.json); production
  assets: [`design/icons/`](../design/icons/).
- `design/designs/support.js` is prototype runtime only and must never be ported or packaged.
- Technical IntelliJ reference, read-only: `/Users/dkwasniak/Workspace/as_plugin`.
- Behavioral ADB reference, read-only: `https://github.com/classops/ADBHelper`.
- ADBHelper has no declared license: use it only for behavioral understanding; copy no code, parser,
  names, strings, or UI.
- The design in `design/` is final. Tasks 010–041 build neutral functional UI and presentation state;
  tasks 042–049 apply the supplied visuals. No task may invent visual rules or copy `as_plugin` UI.
- The handoff specifies the Settings destination and fields, Wi-Fi pairing entry point, and scrcpy
  option semantics, but not complete bespoke layouts for those secondary flows. Tasks 000 and 049
  must use native IntelliJ patterns plus supplied tokens/components rather than inventing new screens.

## Design reference routing

| Area | Primary references |
|---|---|
| Architecture/platform boundary | `design/IMPLEMENTATION.md` §§1–3, subject to task 000 ADRs |
| ADB/scrcpy behavior | `design/IMPLEMENTATION.md` §4; `design/README.md` Interactions & State model |
| Global host/device bar/rail/status/feedback | `design/README.md` Global layout, §§1–2, §8, Interactions; Plugin and IA prototypes |
| Device/mirroring/capture/facts | `design/README.md` §3; Plugin prototype; matching action SVGs |
| Apps | `design/README.md` §4; Plugin prototype; Apps action SVGs |
| Display | `design/README.md` §5; Plugin prototype; `tokens.json` presets; Display action SVGs |
| Network | `design/README.md` §6; Plugin prototype; proxy SVG |
| Logcat | `design/README.md` §7; Plugin prototype; `tokens.json` severity values; Logcat SVGs |
| Settings/pairing/scrcpy options | IA Settings entry; README entry points/state model; IMPLEMENTATION command/settings notes; native IntelliJ UI |
| Design primitives/assets | Design System and Icons prototypes; `tokens.json`; `design/icons/`; README Design tokens & Assets |
| Accessibility/responsive/release evidence | README Responsive/Keyboard/Interactions; IA degradation; IMPLEMENTATION §5 |

## Foundation and dependency path

```text
000 -> 001 -> 002 -> 003 -> 005 -> 006 -> 007 -> 008 -> 009 -> 010 -> 012 -> 014
                    \-> 004 ------/
```

Foundation is complete after task 014. Tasks 000–014 establish architecture/KMP decisions, module
boundaries, deterministic TDD and coverage, process/ADB abstractions, tool discovery, IntelliJ
lifecycle, selected-device architecture, neutral UI hosting, routing, feedback, and shared policies.

The final convergence path is:

```text
feature lanes -> 041 (override convergence where applicable)
042 (repo design primitives) -> 043–049 visual lanes -> 050 -> 051 -> 052 -> 053
```

## Parallel feature lanes

Once their listed dependencies are complete, separate agents may work in these feature-local lanes:

- Device: 015 -> 016
- scrcpy: 017 -> 018
- Capture: 019 -> 020
- Apps: 021 -> 022 -> 023 -> 024 -> 025
- Display: 026, 027, and 028 in parallel -> 029
- Network: 030 and 031 in parallel -> 032
- Logcat: 033 -> 034; then 035 and 036 as dependencies permit -> 037
- Secondary flows: 038; then 039 and 040 as dependencies permit
- After task 042: global chrome 043, Device/Capture 044, Apps 045, Display 046, Network 047,
  Logcat 048, and Settings/secondary 049 may run in parallel.

To preserve parallelism, each feature owns its commands, parsers, state, persistence adapter, and UI
files. Features depend on the stable gateway/aggregate contracts from tasks 003 and 014. Do not add a
global command builder, giant ViewModel, shared feature-state file, or monolithic persistence component.
Global shortcut/resource registration is finalized after feature behavior is stable rather than by
concurrent edits to `plugin.xml`.

## Ordered tasks

| ID | Task | Outcome |
|---:|---|---|
| 000 | [Architecture decisions](000-architecture-decisions.md) | Binding architecture, KMP, state, lifecycle, and adapter ADRs |
| 001 | [Project bootstrap and quality](001-project-bootstrap-quality.md) | Multi-module build, TDD support, CI, architecture and 80% coverage gates |
| 002 | [Process execution](002-process-execution.md) | Central cancellable text/stream/binary JVM process adapter |
| 003 | [ADB contracts and command model](003-adb-contracts-command-model.md) | Stable serial-safe KMP gateway and feature-local extension seams |
| 004 | [Tool discovery](004-tool-discovery.md) | Isolated adb/scrcpy and SDK/path resolution |
| 005 | [ddmlib transport](005-ddmlib-transport.md) | Normalized Android Studio ADB adapter |
| 006 | [Binary ADB transport](006-binary-adb-transport.md) | Binary adapter and side-effect-safe fallback |
| 007 | [IntelliJ composition/lifecycle](007-intellij-composition-lifecycle.md) | ToolWindow registration, DI, EDT and disposal ownership |
| 008 | [Device discovery](008-device-discovery.md) | Device parser and hotplug-aware repository |
| 009 | [Selected-device state](009-selected-device-state.md) | Per-project explicit selection, persistence, and command guard |
| 010 | [UI host infrastructure](010-ui-host-infrastructure.md) | Neutral view/overlay slots and feature registration, no design system |
| 011 | [Device bar/picker](011-device-bar-picker.md) | Functional device context selection and refresh |
| 012 | [Navigation/routing](012-navigation-routing.md) | Persistent feature routing without a central UI hotspot |
| 013 | [Feedback/status](013-feedback-status.md) | Bounded non-modal feedback and status channel |
| 014 | [Device-context policies](014-device-context-policies.md) | Enablement and feature-local badge/process/override aggregates |
| 015 | [Device facts/report](015-device-facts-report.md) | Parsed facts, partial states, clipboard report |
| 016 | [Device basic actions](016-device-basic-actions.md) | Reboot, Wake, and Open shell |
| 017 | [scrcpy session](017-scrcpy-session.md) | Mirroring process/options state and lifecycle |
| 018 | [scrcpy presentation](018-scrcpy-presentation.md) | Functional mirroring controls and action wiring |
| 019 | [Screenshot capture](019-screenshot-capture.md) | Binary-safe PNG save and Reveal |
| 020 | [Screen recording](020-screen-recording.md) | Recording/pull/cleanup/elapsed lifecycle |
| 021 | [Package discovery](021-package-discovery.md) | Packages, labels, debuggable metadata, bounded enrichment |
| 022 | [Apps selection/search](022-apps-selection-search.md) | Virtualized searchable list and shared package selection |
| 023 | [App lifecycle actions](023-app-lifecycle-actions.md) | Force-stop, launch, and truthful restart |
| 024 | [Clear app data](024-app-clear-data.md) | Isolated confirmed clear-data workflow |
| 025 | [Uninstall app](025-app-uninstall.md) | Isolated confirmed uninstall and list reconciliation |
| 026 | [Font scale](026-font-scale.md) | Validate/apply/read back/reset font scale |
| 027 | [Display density](027-display-density.md) | Parse/calculate/apply/read back/reset density |
| 028 | [Display quick toggles](028-display-quick-toggles.md) | Theme, animations, and show-touches behavior |
| 029 | [Display presentation](029-display-presentation.md) | Functional Display binding and aggregates |
| 030 | [Proxy core](030-proxy-core.md) | Validate/read/enable/read back/reset global proxy |
| 031 | [Host IP discovery](031-host-ip-discovery.md) | Cross-platform LAN IPv4 adapter/policy |
| 032 | [Network presentation/recents](032-network-presentation-recents.md) | Functional Network binding and per-project MRU |
| 033 | [Logcat parser](033-logcat-parser.md) | Incremental UTF-8/threadtime parsing |
| 034 | [Logcat buffer/session](034-logcat-buffer-session.md) | Bounded history and cancellable stream lifecycle |
| 035 | [Logcat filtering/pause](035-logcat-filtering-pause.md) | Level/search/package PID filters and unseen state |
| 036 | [Logcat virtualized renderer](036-logcat-virtualized-renderer.md) | Bounded/coalesced 10k+ line rendering |
| 037 | [Logcat controls/presentation](037-logcat-controls-presentation.md) | Functional toolbar, persistence, shortcuts, and states |
| 038 | [Settings](038-settings-persistence-ui.md) | Approved path/capture/buffer persistence and native UI |
| 039 | [Wi-Fi pair/connect](039-wifi-pair-connect.md) | Validated secret-safe wireless pairing flow |
| 040 | [scrcpy options](040-scrcpy-options.md) | Typed/persisted mirroring options |
| 041 | [Override reconciliation](041-override-reconciliation.md) | Truthful reset-all and reconnect re-apply coordination |
| 042 | [Final design system/assets](042-final-design-system-assets.md) | Integrate only supplied tokens, primitives, and production assets |
| 043 | [Global chrome visual design](043-global-chrome-visual-design.md) | Apply supplied design to host, device context, navigation, status, feedback |
| 044 | [Device/Capture visual design](044-device-capture-visual-design.md) | Apply supplied Device, scrcpy, screenshot, and recording visuals |
| 045 | [Apps visual design](045-apps-visual-design.md) | Apply supplied Apps/list/action/confirmation visuals |
| 046 | [Display visual design](046-display-visual-design.md) | Apply supplied Display/override visuals |
| 047 | [Network visual design](047-network-visual-design.md) | Apply supplied Network/proxy/recents visuals |
| 048 | [Logcat visual design](048-logcat-visual-design.md) | Apply supplied Logcat toolbar/row/state visuals |
| 049 | [Settings/secondary visual design](049-settings-secondary-visual-design.md) | Apply supplied Settings/pairing/options/reapply visuals |
| 050 | [Accessibility/keyboard](050-accessibility-keyboard.md) | Complete assistive metadata, focus, and shortcut behavior |
| 051 | [Responsive/scaling verification](051-responsive-scaling-verification.md) | Apply and verify only supplied breakpoints and scaling rules |
| 052 | [Cross-feature integration](052-cross-feature-integration.md) | Regression-tested shared state/lifecycle behavior |
| 053 | [Release verification](053-release-verification.md) | Compatibility, verifier, package inspection, evidence-only release commit |

## Product coverage map

| Product area | Tasks |
|---|---|
| Multi-device discovery, states, selection, refresh | 008, 009, 011 |
| Global navigation, status, feedback, contextual disabling | 012–014 |
| Device facts/report/reboot/shell/wake | 015–016 |
| scrcpy mirroring and options | 017–018, 040 |
| Screenshot and recording | 019–020 |
| Packages/search/actions/clear/uninstall | 021–025 |
| Font scale, density, theme, animations, touches | 026–029 |
| Proxy, computer IP, recents | 030–032 |
| Logcat stream/parser/buffer/filters/pause/rendering/controls | 033–037 |
| Settings, Wi-Fi pairing, reconnect overrides | 038–041 |
| Final design, accessibility, responsiveness | 042–051 |
| Integration, compatibility, packaging | 052–053 |

## Rules for every implementation task

- Write a meaningful failing behavior test first and confirm it fails for the intended reason; then
  implement the minimum behavior, refactor with tests green, and run broader validation.
- Normal automated tests use fakes/fixtures and require no physical device, adb, scrcpy, real clock,
  real user network, or IDE profile. Real-device/tool scenarios are opt-in smoke tests only.
- Maintain at least 80% automated coverage for changed measurable production logic.
- Keep domain/application KMP-ready: no IntelliJ, Swing, ToolWindow, `ProcessBuilder`, filesystem,
  environment, or JVM networking types. Platform behavior is behind inward-defined ports.
- Every device command carries the exact selected serial. UI never invokes raw ADB/process APIs.
- Never block EDT; cancellation, disposal, bounded output, and stale-result suppression are required.
- Respect Scope/Out of scope and finish with one clean logical commit; do not begin the next task.
