# 069 — To check: the TalkBack quick toggle turns off every other accessibility service

Date: 2026-10-16. Found during the task 068 PoC on the Pixel 9. Status: **to check / decide**, not started.

## What happened

During the TalkBack speech PoC (task 068) the Pixel had two accessibility services on: TalkBack and the PoC's
*A11y Focus Logger*. Twice in one session the logger disappeared from `enabled_accessibility_services`:

- 09:35–09:51: TalkBack and the logger were both off, `enabled_accessibility_services` was `null`, while the
  logger's TTS engine stayed the default, so the phone was silent.
- ~10:12: the list held TalkBack only, written with its full component name
  (`com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`).

`adb shell dumpsys settings` shows the last write came from `pkg:com.android.shell`, i.e. over adb, and the PoC
scripts write the short form (`…/.TalkBackService`). The full form and the `null` match Device Cockpit's
TalkBack quick toggle.

## Cause (from the code)

`domain/src/main/kotlin/io/github/dkej123/devicecockpit/domain/display/TalkBackCommand.kt`:

```kotlin
private const val OFF_LINE =
    "settings put secure $SERVICES_SETTING null && settings put secure accessibility_enabled 0"

private fun onLine(service: String) =
    "settings put secure accessibility_enabled 1 && settings put secure $SERVICES_SETTING $service"
```

- **On** replaces the whole list with TalkBack.
- **Off** writes `null` and `accessibility_enabled 0`.

The KDoc already says so ("the default commands replace the whole enabled-services list, which also turns off
any other accessibility service that was on"), so it is known behavior, not an accident. It still hurts:

- a user's own services (Switch Access, Voice Access, a password manager's autofill helper, a test service)
  are turned off by toggling TalkBack in Device Cockpit, and not turned back on;
- a future "TalkBack walk" (task 068) needs TalkBack and a logger service on together; one toggle click breaks
  the walk silently (no `[focus]` records, the walk ends as `stopped`);
- turning TalkBack off this way leaves a non-default TTS engine without any hint.

## To check

1. Confirm the repro on a device: enable another accessibility service, toggle TalkBack on and off in the
   Device view, read `adb shell settings get secure enabled_accessibility_services` after each step.
2. Was the replace-everything behavior a deliberate choice (ADR, task 028/046 or a user decision)? Look before
   changing it.
3. Proposed behavior: **on** appends the TalkBack component to the existing list (no duplicate, match short and
   full form), **off** removes only TalkBack and sets `accessibility_enabled 0` only when the list ends up empty.
   Custom commands from Settings stay as they are.
4. State detection (`ScreenAndInputTools.kt:134`, quick toggle state) uses `contains("talkback")`: check it still
   reads right with several services in the list.
5. Does the MCP side change accessibility services anywhere else in the same way?
6. Tests: `TalkBackCommandTest`, `QuickTogglesViewModelTest` (fixed strings today), `DisplayE2ETest` (deletes the
   setting before the run).

## Out of scope

- The TalkBack walk itself (task 068).
- Restoring the TTS engine (belongs to the walk feature's restore logic).
