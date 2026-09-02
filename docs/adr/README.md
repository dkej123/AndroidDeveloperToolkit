# Architecture Decision Records

Binding decisions for the ADB Toolbox IntelliJ/Android Studio plugin. Every implementation task
must comply with these ADRs; a task that conflicts with one must flag the conflict rather than
silently deviate (see `AGENTS.md`).

Format: Title, Status, Context, Decision, Consequences, Rejected alternatives.

| ADR | Title |
|---:|---|
| [0001](0001-module-layout-and-dependency-direction.md) | Module layout and dependency direction |
| [0002](0002-kmp-readiness-strategy.md) | KMP-readiness strategy |
| [0003](0003-platform-and-toolchain-baseline.md) | Platform and toolchain baseline |
| [0004](0004-presentation-architecture-and-lifecycle-ownership.md) | Presentation architecture and lifecycle ownership |
| [0005](0005-adb-transport-strategy.md) | ADB transport strategy (ddmlib vs. binary, fallback rules) |
| [0006](0006-persistence-ownership-and-state-components.md) | Persistence ownership and state-component policy |
| [0007](0007-device-behavior-policies.md) | Device behavior policies (labels, Open shell, capture coexistence) |
| [0008](0008-design-source-of-truth.md) | Design source of truth and secondary-flow treatment |
| [0009](0009-reference-repository-usage.md) | Reference repository usage (ADBHelper, as_plugin) |

## Status legend

- **Accepted** — binding, in effect.
- **Superseded by NNNN** — replaced; kept for history.
