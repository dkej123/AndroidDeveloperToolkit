# 0009 — Reference repository usage (ADBHelper, as_plugin)

## Status

Accepted

## Context

Task 000's scope requires recording "clean-room use of the unlicensed, local Git-ignored ADBHelper
reference pinned by `references/README.md`, its limited task routing, and read-only use of
`as_plugin`." Both repositories are already governed by `AGENTS.md` and `references/README.md`;
this ADR fixes those rules as a binding architecture decision so later tasks cite one authority
instead of re-deriving the same constraint, and so the routing tables (already defined in
`tasks/README.md` and `.claude/skills/adb-development/references/adbhelper.md`) are adopted rather
than restated with drift risk.

## Decision

- **`references/ADBHelper`** (local, Git-ignored, pinned to revision
  `237d525cee8d55f26df7aa046e54cca7d5c26bf2` of `https://github.com/classops/ADBHelper`, no
  declared license): treated as **all-rights-reserved, behavior/architecture reference only**.
  - Clean-room rule: engineers/agents may *read* it to understand behavior (e.g. what a ddmlib
    device listener needs to handle, how a shell response is parsed) and then write this
    project's own implementation from that understanding — never copy or adapt its code, parsers,
    variable/function/class names, string literals, resources, or UI, and never modify the local
    checkout.
  - It is inspected **only** for the tasks it has a real counterpart for, per the routing table
    already fixed in `tasks/README.md`'s "ADBHelper reference routing" section (tasks 003, 005,
    007, 008/009/011, 015, 021–024) and the clean-room specifics in
    `.claude/skills/adb-development/references/adbhelper.md`. This ADR adopts that table as
    binding rather than duplicating it; a task not listed there does not open the checkout.
  - It is never treated as a product specification or a source of authority over
    `design/`/ADRs/tests/public platform docs — where its behavior conflicts with this project's
    design or ADRs, the design/ADRs win.
  - It is never a build dependency, never referenced by group/artifact coordinates, and never
    packaged.
- **`/Users/dkwasniak/Workspace/as_plugin`**: treated as a **read-only technical reference for
  IntelliJ Platform/Gradle mechanics only** — Gradle module setup, `plugin.xml` structure,
  ToolWindow registration, `runIde`, packaging, and (per ADR 0003) realistic proven version
  combinations. Never modified. Its **visual design/UI is never inspected for implementation
  purposes and never copied** — `design/` is the sole visual authority (ADR 0008). Its business
  logic is not reused (it is a different product); only build/platform-configuration patterns are
  eligible for reference.

## Consequences

- Every task that touches ADBHelper-routed areas has one place (this ADR + the existing routing
  table) confirming it is in-scope for inspection, instead of individually justifying the read.
- No licensing exposure: nothing from either reference repository is copied into shipped code,
  keeping the project's own license posture clean regardless of ADBHelper's unlicensed status.
- `as_plugin`'s validated Kotlin/Gradle/IntelliJ-Platform-plugin versions (ADR 0003) can be cited
  as precedent without any risk of also importing its visual design by association.

## Rejected alternatives

- **Treat ADBHelper as a dependency or vendor its code/resources.** Rejected: it has no declared
  license; `AGENTS.md` and `references/README.md` already forbid this, and doing so would create
  unresolvable licensing risk for a shipped Marketplace plugin.
- **Allow unrestricted inspection of ADBHelper for any task "for general context."** Rejected:
  `tasks/README.md`'s ADBHelper routing table already limits inspection to tasks with a real
  counterpart, explicitly noting there is no corresponding ADBHelper implementation for binary
  ADB, scrcpy/capture, display, network/proxy, logcat, uninstall, Wi-Fi pairing, or final visuals
  — inspecting it for those areas would risk unconsciously converging on its design with nothing
  to clean-room against.
- **Use `as_plugin`'s UI/visual patterns as a starting point "since it's a working plugin."**
  Rejected: `AGENTS.md` explicitly forbids copying its visual design; `design/` is final and
  independently specified (ADR 0008).
