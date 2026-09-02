# 0006 — Persistence ownership and state-component policy

## Status

Accepted

## Context

Task 000's scope requires deciding "project/application persistence ownership and feature-local
state-component policy." `design/README.md`'s "State model" lists exactly what is persisted per
project: `selectedDeviceSerial`, `selectedPackage`, `proxyHost`, `proxyPort`, `recentProxies`,
`logMinLevel`, `logPackageFilterOn`, `logWrap`, `captureDir`, `adbPath`, `scrcpyPath`, `lastView`.
It also says device-context (bar selection) scopes all views and persists "per project across IDE
restarts," and that applied overrides are remembered "per serial" and offered for re-apply on
reconnect. `tasks/README.md` explicitly forbids "a ... monolithic persistence component" and
requires each feature to own its persistence adapter. `design/IMPLEMENTATION.md` names one
`settings/AdbToolboxSettings.kt` `PersistentStateComponent` as a suggestion, which this ADR either
confirms or supersedes.

## Decision

- **Scope: project-level only, for everything in `design/README.md`'s "State model" persisted
  list.** Every field named there (`selectedDeviceSerial` through `lastView`) is genuinely
  per-project data (a device/proxy/log setting chosen while working in *this* project), so it is
  stored via `com.intellij.openapi.components.PersistentStateComponent` scoped
  `@State(... storages = [Storage("adbToolbox.xml")])` at the **project** level (`Service.Level.PROJECT`),
  not application level. There is currently no field in the design that is legitimately
  IDE-wide/user-wide (e.g. no cross-project "always use this adb path" is specified) — if a future
  task introduces one, it gets its own application-level `PersistentStateComponent`, it is not
  folded into the project one.
- **Feature-local state components, not one monolith:** each feature that persists state owns a
  small `data class` **state carrier** and a thin adapter implementing a `:domain`
  `SettingsRepository`-style port for just its fields, e.g. `DeviceSelectionState`
  (`selectedDeviceSerial`), `AppsState` (`selectedPackage`), `NetworkState` (`proxyHost`,
  `proxyPort`, `recentProxies`), `LogcatState` (`logMinLevel`, `logPackageFilterOn`, `logWrap`),
  `ToolPathsState` (`adbPath`, `scrcpyPath`), `NavigationState` (`lastView`). These carriers are
  **composed into one root `PersistentStateComponent`** (`AdbToolboxProjectState`, one XML file,
  one `getState()`/`loadState()` pair, matching `design/IMPLEMENTATION.md`'s suggested class name)
  so the IDE serializes one coherent blob per project — but each feature reads/writes only its own
  slice through its own small port/adapter pair, and no feature reaches into another feature's
  slice. "One state component" (IDE mechanism) and "one shared mutable state object used across
  features" (the thing `tasks/README.md` forbids) are different things; this decision is the
  former, not the latter.
- **Per-serial applied overrides** (font scale, density, proxy — `design/README.md`'s "remember
  applied overrides per serial and offer to re-apply") are a distinct persisted map,
  `serial -> AppliedOverrides`, owned by the Device-context/override-aggregate feature (task 014,
  reconciled in task 041), stored in the same project `PersistentStateComponent` as its own slice
  — not duplicated into each of Display/Network's own state carriers, since it is inherently
  cross-feature (status-bar "N overrides" chip) rather than one feature's concern.
- **Runtime (non-persisted) state** — `devices[]`, `apps[]`, `mirroringProcess`,
  `recordingProcess`, `logBuffer`, `toasts[]`, `pendingConfirm`, etc. — never goes through
  `PersistentStateComponent`; it lives only in each feature's MVI `ViewState` (ADR 0004) and is
  rebuilt on tool-window open/project load.
- Persistence reads/writes go through the same dispatcher-injected pattern as ADB calls (ADR 0004)
  — never blocking the EDT for XML (de)serialization of anything non-trivial, even though
  `PersistentStateComponent` itself is typically fast enough to be synchronous; large or
  potentially slow persistence work is dispatched off EDT regardless.

## Consequences

- One physical XML file (`adbToolbox.xml`) per project, which is what users expect to find under
  `.idea/`, while still giving each feature an isolated read/write surface it owns and tests in
  isolation.
- No feature needs to guess whether its setting belongs at project or application scope — this ADR
  fixes it as project-scope for every field the design currently specifies.
- Adding a feature's persisted field later is additive (a new slice + a new property on the root
  state `data class`), not a renegotiation of where persistence lives.
- The override-reconciliation task (041) has one clear place (the override slice) to read from
  without reaching into Display/Network's carriers.

## Rejected alternatives

- **Application-level (global, cross-project) persistence.** Rejected: `design/README.md` states
  these values "persist per project across IDE restarts," not across all projects; an
  application-level store would leak one project's selected device/proxy into an unrelated
  project.
- **One `PersistentStateComponent` per feature (five-plus separate XML files).** Rejected: adds
  IDE-level component registration overhead per feature for no isolation benefit beyond what
  feature-local state carriers inside one root already give, and fragments what is conceptually
  one project's plugin settings across many files.
- **One flat, unstructured state `data class` with all fields inline, read/written by any
  feature.** Rejected: this is the "monolithic persistence component" `tasks/README.md` explicitly
  forbids — it would let any feature accidentally read or mutate another feature's fields.
- **Store applied overrides duplicated inside each of Display's and Network's own state.**
  Rejected: the status bar's combined "N overrides · reset all" and per-serial reconnect re-apply
  are inherently cross-feature; duplicating the map risks the two copies drifting.
