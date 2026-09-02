# 023 — App force-stop, launch, and restart

## Goal

Implement the three reversible app lifecycle actions for the exact selected device/package.

## Dependencies

- [013 — Feedback/status](013-feedback-status.md)
- [014 — Device-context policies](014-device-context-policies.md)
- [022 — Apps selection/search](022-apps-selection-search.md)
- Restart/force-stop/launch behavior and copy in `design/README.md` §4; command references in
  `design/IMPLEMENTATION.md` §4; restart/forceStop action SVGs.
- Inspect only the task-023 ADBHelper launch/default-activity behavior routed in
  `.claude/skills/adb-development/references/adbhelper.md`; ADBHelper has no force-stop/restart workflow.

## Scope

- Add feature-local command factories/use cases for force-stop and launcher start.
- Implement restart as force-stop followed by launch with explicit partial-failure reporting.
- Resolve the approved launcher strategy without shell interpolation and preserve diagnostics.
- Prevent duplicate in-flight actions and bind minimal controls/global restart intent.

## Out of scope

- Clear data, uninstall, package discovery, and final visual design.

## TDD plan

1. Write failing tests for exact serial/package, command values, success, non-zero/unknown status,
   timeout, cancellation, disconnect, duplicate request, missing launcher, and restart second-step failure.
2. Write failing presenter/action tests, then implement after failures with fake ADB.

## Acceptance criteria

- Restart never reports success if launch fails after force-stop.
- UI contains no ADB logic; all actions are guarded, cancellable, and at least 80% covered.

## Validation

- Run command/use-case/presenter tests and static architecture checks.
