# 067 — Reset one permission or all; MCP permission control; quieter Current app header

Date: 2026-10-09. User requests the same day.

## Goal

Reset permissions should let the user choose all granted permissions or a single one; agents
should be able to read and change permissions one by one; the Current app header should stop
flickering.

## Dependencies

- 062 (Current app), 065 (MCP tools).

## Scope

1. **Current app › Reset permissions**: the button keeps resetting every granted runtime
   permission; a caret next to it opens a menu "All granted permissions" + one item per granted
   permission (short name, full name in the tooltip; system/policy-fixed ones disabled).
   `PermissionReset.plan(only =)`, `CurrentAppUseCase.resetPermissions(permission =)`,
   `CurrentAppViewModel.resetPermission(pkg, permission)`; toast "CAMERA reset for <pkg>".
2. **MCP**: `manage_app reset_permissions` takes `permission`; new `list_permissions` (read only:
   granted / denied / fixed) and `set_permission` (grant, revoke, reset one permission; Full control),
   both on `PermissionCommands` (validated names) and `dumpsys package`.
3. **Current app header**: the "updated N s ago / reading…" meta is removed (it changed with every
   3 s poll); a 22px refresh icon button (tooltip "Refresh now — also checks every 3 s while this
   view is visible") replaces it. The "X came to the front · Update" link stays.

## Out of scope

- Grant from Current app (App details › Permissions already grants and revokes one by one).

## TDD plan

- Domain plan with `only`; use case revokes only the chosen permission; VM event text;
  MCP list/set/reset and invalid names; section menu items, disabled fixed items, refresh click.

## Acceptance criteria

- One permission can be reset from Current app without touching the others.
- An agent can list permissions in Read only and grant/revoke/reset one in Full control.
- The Current app header no longer changes text on every poll.

## Validation

`./gradlew clean build koverVerify`; visual goldens regenerated in a full `:intellij:test` run.
