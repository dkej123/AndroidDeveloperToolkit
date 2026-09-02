# 011 — Device context bar and picker behavior

## Goal

Expose the selected-device state and keyboard-accessible selection/refresh behavior in the UI host.

## Dependencies

- [009 — Selected-device state](009-selected-device-state.md)
- [010 — UI host infrastructure](010-ui-host-infrastructure.md)
- Inspect the task-011 ADBHelper picker interactions through
  `.claude/skills/adb-development/references/adbhelper.md`; do not reuse its UI or modal architecture.
- `design/README.md` §1 Device bar, the device-state controls in
  `design/designs/ADB Toolbox Plugin.dc.html`, IA device-context region, and refresh/authorize assets
  under `design/icons/actions/`.

## Scope

- Map loading, none, online, unauthorized, offline, and error states to presentation models.
- Implement a platform device chooser keyed by exact serial, including duplicate model names and Wi-Fi serials.
- Support mouse plus Up/Down/Enter/Escape and a refresh intent with duplicate-refresh suppression.
- Expose a Pair-over-Wi-Fi intent for task 039 without inventing that flow.
- Marshal updates to EDT and dispose subscriptions/popup resources.

## Out of scope

- Discovery parsing, selection persistence, pairing implementation, colors, spacing, icons, typography,
  final copy/layout, or other visual-design decisions.

## TDD plan

1. Write failing presenter tests for every device state and multiple-device identity cases.
2. Write failing interaction tests for exact-serial selection, refresh suppression, and keyboard behavior.
3. Implement after failures, then add EDT/disposal tests using fake state sources.

## Acceptance criteria

- The UI never selects by name/index and never treats unauthorized/offline as command-eligible.
- Refresh and picker behavior work without blocking EDT or requiring hardware.
- Visual treatment remains deferred to task 042; changed logic reaches 80% coverage.

## Validation

- Run presenter/component/keyboard/disposal tests and the platform suite.
