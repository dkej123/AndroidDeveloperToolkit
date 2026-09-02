# 0004 — Presentation architecture and lifecycle ownership

## Status

Accepted

## Context

Task 000's scope requires choosing "MVVM or MVI, immutable UI-state conventions, dispatcher
injection, and ownership of project/tool-window coroutine scopes and `Disposable` lifecycles."
`design/README.md`'s "State model" section already separates persisted per-project state from
runtime view-model state (`devices[]`, `deviceState`, `apps[]`, `fontScale`, `mirroringProcess`,
`toasts[]`, `pendingConfirm`, ...) and lists derived state (`overrideCount`). `tasks/README.md`
warns against "a giant ViewModel" and requires each feature to own its state; the
intellij-plugin-development skill requires "the project's selected MVVM/MVI architecture" be
respected and that "UI layer consumes state and emits actions/intents; it must not contain
business logic." Task 007 (IntelliJ composition/lifecycle) and every feature-presentation task
(011, 018, 022, 029, 032, 037, 040) need this decided once, not re-derived per feature.

## Decision

- **Pattern: MVI**, one small `ViewModel`-equivalent per feature (not one plugin-wide ViewModel),
  living in `:application` (KMP-ready per ADR 0002) and consumed by a thin `:intellij` view. Each
  feature ViewModel exposes:
  - An immutable `data class`/`sealed interface` **ViewState** (e.g. `DeviceViewState`,
    `AppsViewState`) — every field matching `design/README.md`'s runtime state for that view.
    State is replaced wholesale on each reduction (`StateFlow<ViewState>`), never mutated in place.
  - A sealed **Intent** type for user/system-triggered inputs (e.g. `AppsIntent.Search`,
    `DeviceIntent.StartMirroring`), fed through one `handle(intent: Intent)` entry point.
  - One-shot **Effect**s (sealed type) for things that are not state — toast dispatch, "reveal in
    Finder," navigation-once events — delivered via a `Channel`/`SharedFlow`, never folded into
    ViewState (this matches the design's toast/status-bar model, which is a log of events, not a
    single persistent field).
  - Reduction is a pure function `(ViewState, Intent) -> ViewState` (plus a suspend side-effect
    path for intents that call into `:domain` use cases), unit-testable with no coroutine
    dispatcher or IntelliJ fixture, per ADR 0002 and adb-development's testing rule.
- **Immutability:** ViewState types use `val`, immutable collections (`List`/`Map`, not
  `MutableList`), and are safe to read from any thread; only the owning ViewModel mutates the
  backing `MutableStateFlow`.
- **Dispatcher injection:** every ViewModel/use case that launches coroutines takes an injected
  `CoroutineDispatcher` (or a small `DispatcherProvider` with `default`/`io`/`main` slots) rather
  than referencing `Dispatchers.IO`/`Dispatchers.Default` directly, so tests substitute a test
  dispatcher and production wiring substitutes `Dispatchers.IO`/EDT-backed dispatchers. UI-facing
  results are always marshaled back onto the EDT dispatcher by the `:intellij` view layer, never
  assumed by `:application`.
- **Coroutine scope ownership:** one project-level `CoroutineScope` owned by the `:intellij`
  project service that composes the ToolWindow (task 007), tied to a `Disposable` registered with
  the project (`Disposer.register(project, ...)` or `CoroutineScope(...).asDisposable()`
  equivalent). Each feature ViewModel receives a child scope (`projectScope +
  SupervisorJob()`-per-feature or `projectScope.newChildScope()`) so one feature's failure/cancel
  does not tear down others, and so a feature can be disposed independently if the tool window
  content is rebuilt. Long-running sessions with their own lifecycle (scrcpy mirroring, screen
  recording, Logcat streaming) get their own child scope tied to the *session's* Disposable (e.g.
  disposed when mirroring stops or the tool window closes), not the project scope directly, so
  cancelling one session cannot cancel unrelated scopes.
- **Disposable ownership:** every `Disposable` (per-feature listeners, the process-backed
  sessions above, popups) is registered against the narrowest scope that outlives it — feature
  content Disposable under the tool-window content Disposable, tool-window content Disposable
  under the project. Nothing is registered against `ApplicationManager` unless it is genuinely
  application-scoped (ADR 0006).
- UI (`:intellij`) code only renders `ViewState` and dispatches `Intent`s; it never calls
  `:adapters-adb`/`:adapters-jvm` directly and never contains branching business logic, per
  architecture-guardrails and intellij-plugin-development.

## Consequences

- Feature ViewModels/reducers are pure, KMP-ready, and unit-testable without EDT, ADB, or a
  physical device.
- The Effect channel gives toasts/status-bar messages (design's non-modal, multi-toast model) a
  natural home instead of being crammed into ViewState.
- Explicit scope/Disposable ownership prevents the "never block the EDT" and "coroutine/process
  lifecycles must be properly managed" rules from being reinvented ad hoc per feature task.
- Slightly more boilerplate per feature (ViewState/Intent/Effect triad) than a single shared
  ViewModel, which is the intended trade-off given `tasks/README.md`'s explicit ban on a giant
  shared ViewModel/state file.

## Rejected alternatives

- **MVVM with mutable/observable properties (Swing `PropertyChangeSupport` or per-field
  `StateFlow`s).** Rejected: per-field mutability makes "immutable UI-state conventions" (an
  explicit scope item) unenforceable, and partial-field updates are exactly the kind of shared
  mutable state architecture-guardrails asks to avoid.
- **One application-wide ViewModel/state object for all five views.** Rejected: directly
  contradicts `tasks/README.md`'s "do not add ... a giant ViewModel ... or a shared feature-state
  file," and would force unrelated features (e.g. Logcat and Proxy) to recompose on every mutual
  state change.
- **Let each feature reference `Dispatchers.IO` directly.** Rejected: makes fast deterministic
  tests (required by adb-development/tdd-implementation) dependent on real threads/timing and
  blocks swapping dispatchers for tests.
- **One coroutine scope for the whole plugin (`GlobalScope`-like singleton).** Rejected: a
  long-running session (logcat, mirroring) cancelling or crashing would have no isolated blast
  radius, and disposal could not be tied to project/tool-window/content lifecycle as required.
