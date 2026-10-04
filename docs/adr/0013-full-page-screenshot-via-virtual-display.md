# 0013 — Full-page screenshots on a tall virtual display via the device helper

## Status

Accepted (2026-10-02). Extends ADR 0010/0012's device helper; ADR 0005's transport rules are unchanged.

## Context

The user asked for a screenshot of a screen's whole scrolling content, next to the existing
screenshot of what is visible. Android has no adb command for it; the system's "Capture more" UI
cannot be driven from adb. Options were checked on the user's Pixel 9 (Android 17) on 2026-10-02:

- **`wm size` with a taller logical size** — crashed System UI on every change
  (`DisplayCutout.getSideOverride` via `OneHandedController`) and locked the phone. Rejected.
- **Scroll and stitch** (`input motionevent` drags, `screencap`, row matching) — deterministic
  without fling, but needs image matching, struggles with sticky headers, FABs and animations, and
  must scroll the user's screen back afterwards.
- **A tall virtual display** — `am display move-stack <task> <display>` moved the *current* task to
  a 1080×6000 virtual display: the top activity is recreated there from its saved state (same screen,
  back stack intact), lays out its lists at the full height, and one frame holds the whole content.
  Moving it back recreates it again; the scroll position is lost. Removing a virtual display that
  was created with `DESTROY_CONTENT_ON_REMOVAL` destroys the task on it.
- **Scroll Capture API / an installed app** — an app cannot hold `READ_FRAME_BUFFER`; the shell
  can. Not pursued; the shell already has every permission an app could get.

The user chose the virtual display and accepted the recreation.

## Decision

- A third helper entry point, `FullShotMain <output.png>`, run by `app_process` as the shell:
  1. reads the focused root task (`IActivityTaskManager.getFocusedRootTaskInfo`, API 29–30
     `getFocusedStackInfo`) and refuses home/system tasks;
  2. creates a virtual display as wide as the screen and 3× as tall (at most 8192 px), at the
     screen's density, rendering into an `ImageReader`, through a context presented as
     `com.android.shell` (the system context's `android` package fails the uid check). Flags:
     public, own-content-only, and on API 33+ trusted, own display group, always unlocked —
     never `DESTROY_CONTENT_ON_REMOVAL`, so if the helper dies Android moves the task back;
  3. moves the task there (`moveRootTaskToDisplay`, API 29–30 `moveStackToDisplay`) and waits
     (15 s at most) until the window manager reports the app's window on that display has asked
     for more than the screen's height and drawn (`dumpsys window windows`: `Requested w= h=`,
     `mDrawState=HAS_DRAWN`), and the frames have then stayed still for 800 ms. Frames alone are
     not enough: Android first shows a starting window — a snapshot of the task at the old size —
     and on a slow device the app takes longer than any idle time to lay out again (seen on the
     API 33 emulator). Where the dump cannot be read, it falls back to "a second frame, not a flat
     colour". It **always** moves the task back in `finally` before releasing the display;
  4. trims the uniform rows under the content (keeping 16 dp), writes a PNG to
     `/data/local/tmp/adbtoolbox-fullshot.png`, and reports `file=`, `size=`, `truncated=` — the
     content may continue below when fewer than 16 dp of uniform rows were left.
- Under `adb root` the helper first drops to the shell's uid/gid (`Process.setGid/setUid`): before
  Android 15, creating a virtual display rejects every package name for uid 0.
- The host streams that file with `exec-out cat` through `CaptureScreenshotUseCase`'s existing
  commit/discard path (saved as `<name>-full.png`), then removes it. `:application` sees the helper
  only as the `FullShotRenderer` port.
- The Device view's Capture row gets a secondary **"Full page"** button whose tooltip says the app
  is recreated. A truncated capture is saved with a warning toast.

## Consequences

- The app's current screen is recreated twice and loses its scroll position; state not saved in
  `onSaveInstanceState`/`rememberSaveable` is lost. This is visible to the user (tooltip) and
  can itself reveal state-restoration bugs.
- Bottom-anchored UI (bottom navigation, FABs) sits at the bottom of the tall display, leaving a
  gap above it; content longer than the display is cut and reported.
- Needs Android 10+ (API 29). Moving tasks to an untrusted display on API 29–32 is unverified.
- Hidden APIs are called by name through reflection, as in ADR 0012; the readiness check also
  reads `dumpsys window` text, whose format can change — then the frame heuristic applies.
- Verified on the API 33 emulator (shell and root, Settings home 1080×4763, killing the helper
  mid-capture returns the task to the main screen) and, for the move itself, on a Pixel 9 /
  Android 17 via scrcpy.

## Rejected alternatives

- **`wm size`** — crashes System UI on Android 17 (above).
- **Scroll and stitch** — workable fallback, kept in reserve; more moving parts for a worse
  result on sticky headers and floating UI.
- **scrcpy `--new-display`** — proved the approach, but scrcpy is an optional install of the user's and
  its default destroys the task when the display closes.
