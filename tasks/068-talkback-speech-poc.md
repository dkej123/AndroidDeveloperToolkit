# 068 — PoC: exact TalkBack speech for a whole screen, without swiping by hand

Date: 2026-10-15. User request the same day. Status: **PoC, needs testing on the user's Samsung and
Pixel**. Branch `poc/talkback-speech`, not for `main` until the results below are filled in.

## Goal

For accessibility tests we want the **exact text TalkBack speaks** for every element of a screen
(label, role, state, hint, in TalkBack's order and wording, which differs between Samsung and Pixel),
collected **automatically**, not by swiping control by control and listening. If the PoC holds up, it
becomes a Device Cockpit feature: a "TalkBack walk" action and an MCP tool returning
`[element → spoken text]`.

## What we found

1. **TalkBack has no API for "here is a node, give me its text".** No public method, broadcast or
   IPC. TalkBack only speaks when accessibility focus lands on a node (it also speaks when another
   accessibility service sets focus with `ACTION_ACCESSIBILITY_FOCUS`).
2. **Developer settings don't give us the text in a usable form.**
   - *Display speech output* shows each utterance as a toast: on screen only.
   - *Log output level → Verbose* has to be set by hand in TalkBack's menu and its format is
     undocumented. With the default level, Google TalkBack 16.0 logged no speech text at all
     (checked on API 36: the TTS engine synthesised on every step, logcat had no text).
   - *Node tree debugging* (tag `TreeDebug`) dumps the tree and traversal order, but node properties,
     not the composed utterance.
3. **A silent TTS engine gets the exact utterance.** TalkBack sends everything it says to the default
   TTS engine. An app that registers as a TTS engine and logs instead of speaking receives the final
   text, with role, state and hint, from any TalkBack build that uses the system default engine.
4. **`adb shell input` can't drive TalkBack**: injected events enter after the accessibility input
   filter, so TalkBack never sees them as gestures or shortcuts. A **virtual keyboard created with the
   device shell's `hid` tool** (no root) does go through the filter, so TalkBack's keyboard shortcut
   *Alt + Right* (next element) works. Checked on the pool emulator (API 36, Google TalkBack 16.0):
   TalkBack moved and spoke on each key.
5. **Prior art:** [asanre/a11y-speech-logger](https://github.com/asanre/a11y-speech-logger) (MIT)
   does exactly points 3 and 4:
   - `SpeechLoggerService`, a silent TTS engine, logs `[speech] "<utterance>"` to tag `A11ySpeech`;
   - `FocusLoggerService`, an accessibility service next to TalkBack, logs the focused node
     (`[focus]`: id, class, text, desc, role, state, bounds…); records share `t=` (elapsedRealtime)
     so speech is matched to the element by time;
   - `tools/audit.py capture <name> --auto` walks the screen with the `hid` keyboard and stops when
     focus cycles back, stops moving or leaves the app.

   Upstream has tested it only on Android 12 with a vendor TalkBack 13.1.

## What the PoC did (2026-10-15, pool emulator API 36, Google TalkBack 16.0.0)

Built upstream at `6ac7f9b`, installed, set it as the engine, enabled the focus logger and TalkBack,
ran `capture --auto`. Output (the emulator was overloaded, so the walk went over an ANR dialog instead
of Settings):

```
→ TextView "Pixel Launcher isn't responding" [133,1068][947,1131]
    "Pixel Launcher isn't responding"
→ Button "Close app" [70,1170][1010,1296]
    "Close app"
    "Button"
walk ended: left app
```

So the mechanism works with Google's TalkBack too: the element, then each utterance TalkBack spoke
for it. A full walk of a real screen was **not** completed: the pool emulator was reset twice mid-run.

Gotchas seen:
- Set `tts_default_synth` **before** TalkBack starts; TalkBack binds the engine at start (the script
  turns TalkBack off and on).
- `enabled_accessibility_services` was overwritten once after the first TalkBack start; set it again
  and check `dumpsys accessibility` lists both TalkBack and A11y Focus Logger.
- While the logger is the engine the whole device is **silent**. Always restore.

## How to run it (for the agent testing on the user's devices)

Prerequisites on the host: `adb`, Python 3.9+, JDK 17+, an Android SDK with `platforms;android-37.0`
(`ANDROID_HOME` set, or write `sdk.dir` into the clone's `local.properties`). Device: USB debugging
on, TalkBack installed, screen unlocked and kept awake (`adb shell svc power stayon usb`).

```bash
S=<serial from adb devices>
poc/talkback-speech/run-walk.sh setup   -s $S          # clone pinned upstream, build, install
poc/talkback-speech/run-walk.sh on      -s $S          # saves engine + services, then turns everything on
# open the screen to test on the device, in the state you want
poc/talkback-speech/run-walk.sh walk    -s $S settings-home
poc/talkback-speech/run-walk.sh restore -s $S          # ALWAYS, also after a failure
```

Results go to `~/.cache/devicecockpit-talkback-poc/audits/<date>/<name>/` (`session.txt` = full
log, screenshots, `findings.json`). Nothing is written into the repo. Manual fallback, if the
automatic walk fails: `adb logcat -s A11ySpeech:I -v raw` and swipe right on the phone by hand;
that still proves the speech capture.

If `restore` can't run: `adb shell settings delete secure tts_default_synth` (system default
engine) and switch off *A11y Focus Logger* in Accessibility settings.

## What to test

Run on **both** devices (Samsung with Samsung's TalkBack, Pixel with Google's), recording device
model, Android version and TalkBack version (`adb shell dumpsys package <talkback pkg> | grep versionName`).

| # | Check | How | Pass when |
|---|---|---|---|
| 1 | Engine works | `on`, swipe once by hand, `adb logcat -s A11ySpeech:I -v raw` | `[speech]` lines appear and the phone is silent |
| 2 | Samsung TalkBack uses the default engine | same as 1 on the Samsung | `[speech]` lines appear. If not: Samsung TalkBack pins Samsung TTS — note it, check its TTS setting |
| 3 | `hid` exists | `adb shell command -v hid` | prints a path (Samsung may lack it) |
| 4 | Automatic walk | `walk` on Settings home | ends with `walk ended: cycle` or `stopped`, one `[speech]` group per element, matching what TalkBack says aloud with the stock engine |
| 5 | Wording accuracy | compare 5–10 elements with *Display speech output* toasts (stock engine) | identical text, incl. role/state ("Switch, on", "Double tap to activate") |
| 6 | Own app, Compose | walk a Compose screen of one of the user's apps | ids appear when `testTagsAsResourceId` is set; nothing silent that TalkBack reads aloud |
| 7 | Lists that scroll | walk a long list | walk continues past the first page, no duplicated or skipped items |
| 8 | Dialogs / sheets | open a bottom sheet or dialog, walk | walk stays in the sheet, focus is in it at start |
| 9 | Polish locale | device language PL | Polish utterances arrive unchanged (diacritics intact) |
| 10 | Speed | time a 30-element walk | note seconds per element |
| 11 | Restore | `restore` | voice back, original services list, TalkBack state as before |
| 12 | Side effects | after restore, reboot not needed? other a11y services untouched? | nothing left enabled |

Record per device: pass/fail per row, the full `session.txt` of rows 4 and 6, and anything odd
(keyboard notification, TalkBack hints mentioning a keyboard key instead of double tap — expected,
it comes from the virtual keyboard).

## Results

_To fill in by the testing agent._

| # | Samsung (model / Android / TalkBack) | Pixel (model / Android / TalkBack) |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |
| 6 | | |
| 7 | | |
| 8 | | |
| 9 | | |
| 10 | | |
| 11 | | |
| 12 | | |

## If it works: the product shape (not part of this PoC)

- A small companion APK bundled with the plugin (the existing `app_process` helper can't be a TTS
  engine or an accessibility service). Either our own clean implementation of the two services, or
  upstream under MIT with attribution in `THIRD_PARTY_NOTICES.md`.
- Device view / Layout inspector action **"TalkBack walk"**: saves engine + services, switches,
  walks over `hid`, restores in a `finally`, shows a table *element → spoken text* with the element
  highlighted on the screenshot; export to text/JSON for tests.
- MCP tool `talkback_walk(screen?, max_steps?)` returning `[{node, speech[]}]`, plus
  `talkback_speak(node)` that sets accessibility focus on one node and returns its utterance.
- Restore on plugin/IDE crash: persist the saved state, offer restore on next connect.

## Out of scope

- Any change to plugin code on this branch.
- Activating elements, TalkBack menus, long-press, links inside text.
- iOS / VoiceOver.
