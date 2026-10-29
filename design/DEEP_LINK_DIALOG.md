# Handoff: Deep link dialog

Fire a deep link on the selected device **exactly as if the user tapped a link** somewhere (browser, SMS, notification) — without per-app deep-link discovery.

- Prototype: `designs/ADB Toolbox Plugin.dc.html` → Device view → **Device** section → **Deep link** button.
- Screenshots: `screenshots/deep-link-dialog.png` (handoff 6 baseline) and `screenshots/deep-link-h7/dl-*.png` (handoff 7 states, listed in §10).
- Audience: this doc is written to be executed by an implementing agent — every value and string below is final; do not invent extra fields. **No editor for action / component / flags / extras.**
- Fidelity: high. Tokens as in `tokens/tokens.json` (`panel`, `field`, `header`, `bg`, `border`, `borderStrong`, `text`, `textDim`, `textFaint`, `accent`, `accentBg`, `accentBorder`, `brandBg`, `brandBorder`, `green`, `greenBg`, `amber`, `amberBg`, `red`, `redBg`). Every new element uses these tokens only, so Dark and Light come for free.
- Sections new or changed in this round are marked **(handoff 7)**.

---

## Entry point

- Device view → Device section action row, after Reboot / Open shell / Wake.
- Secondary button, label **"Deep link"** (no ellipsis). Tooltip: "Fire any deep link or intent on this device".
- Disabled (45 %, reason tooltip) when `deviceState != connected`, like every device-mutating control.
- Also registered as `AnAction` `AdbToolbox.OpenDeepLink` (Find Action) and as a Favorites item.

## Surface (handoff 7 — confirms decisions since handoff 6)

- Modal `DialogWrapper(project, canBeParent = true)`, **undecorated** (no OS title bar). The title "Open deep link" lives in the body header row.
  macOS adds a title-bar strip and square corners to such a window unless handled as in
  [`docs/macos-rounded-dialogs.md`](../docs/macos-rounded-dialogs.md).
- Header row: title 12.5/700 left · device meta right (mono 10 `textDim`, "Pixel 9 · 49060DLAQ002W7", ellipsized) · **×** 22×22 `textDim`, tooltip "Close (Esc)". Drag the window by the header row.
- Preferred width **600 px** (`JBUI.scale`), min width 480, height content-driven. Body padding 18 px, body font UI 11 px.
- **The dialog stays open after Open on device** (and after ▸ in Recent). Only × / Close / Esc close it.
- `Esc` closes the topmost open popup first ("Open in" list or handlers popover), then the dialog. `Enter` in the URI field = Open on device.
- **Height budget:** handoff 7 must not make the dialog taller than handoff 6 in its common states. It pays for the new handlers row and result banner by (a) dropping the "Click a row to load it into the form." hint under Recent (now the row tooltip) and (b) collapsing the command preview to one line by default (§6).

## Layout (top → bottom)

Section labels: 9.5 px / 700, letter-spacing 0.5, uppercase, `textDim`, margin 12 px top / 5 px bottom.

### 1. Recent · N (changed in handoff 7)

Shown only when history is non-empty. Label "RECENT · 12".

Table (`JBTable`), 1 px `border`, radius 6, background `bg`.

| column | width | content |
|---|---|---|
| icon | 16 px | app tile 16×16 r5. Debuggable: `brandBg` + `brandBorder`. Other app: `header` + `border`. Any app, and apps outside the user-app list (system apps): the Android robot head in `green` (user decision, 2026-10-29) |
| Link | flex | mono 12 px, single line, ellipsis end. `scheme://host` in `text` 600, rest in `textDim` |
| App | 128 px | UI 11.5 `text`, ellipsis. App label, raw package if not installed, or "Any app". An Any app row whose last launch reached one app (`Activity:` of `am start -W`) shows that app's label and icon; history stores it as `handledBy` (user decision, 2026-10-29) |
| Last run | 64 px | UI 11 `textDim`. "12:40" today, "Yesterday", weekday ("Mon") this week, else "Oct 21" |
| actions | 48 px | ▸ (22×22, `accent`, hover `accentBg`) "Open again on this device" · × (22×22, `textDim`) "Remove from history" |

- **Order: oldest at the top, newest at the bottom.** On open, and after every launch, scroll the table to the bottom so the newest row is visible.
- Column gap 10, row padding 5/6/5/10, min row height 32, 1 px `border` between rows. Header row 10.5/600 `textDim`, `header` bg, sticky.
- Max visible height 230 px; scrolls internally.
- Hover `hover` tint. Loaded row (matches form URI + package): `accentBg`.
- Row tooltip: "Click to load into the form · <full URI>". (The hint line under the table is removed.)
- **Click row** → loads URI + params + package into the form (does not run).
- **▸** → loads the row into the form **and** runs it; the dialog stays open and shows the result (§7).
- **×** → removes the entry, no confirmation.

> Screenshots render the table at scrollTop 0 (capture-tool limitation). In the live prototype it is scrolled to the newest row.

### 2. URI

- Single-line `JBTextField`, height 28, mono 12, full width. Placeholder "myapp://product/42?ref=ide". Focused on open.
- Validation on Open: `^[a-z][a-z0-9+.-]*:\S+` (case-insensitive). Error: red border + 10.5 `red` under the field: "Enter a URI with a scheme, e.g. myapp://path or https://…". Clears on typing. This is input validation only — launch failures go to the result banner (§5).
- Chrome `intent:` links are refused with "intent: links are read by the browser, not sent as a link. Enter the URI they
  wrap, e.g. myapp://path" (deep-link audit, 2026-10-29): sent with `-d` they would be opaque data, not the intent they describe.

### 2b. Who handles this link? (handoff 7)

One row directly under the URI field (above Query parameters). Height 24, margin-top 5, UI 11, single line, no wrap.

**Trigger:** live, **debounced 500 ms** after the last edit of URI, query params or Open in — only when the URI passes validation. Also re-run when the dialog opens with a valid URI, when the selected device changes, and when the IDE frame regains focus (an app may have been installed). Cache by `serial + uri + package`; a stale response (key changed meanwhile) is dropped. Hidden while the URI is empty/invalid. Never launches anything.

**Commands** (off the EDT, 3 s timeout):

```
adb -s <serial> shell cmd package query-activities --brief \
    -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '<uri>'
adb -s <serial> shell cmd package resolve-activity --brief \
    -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '<uri>'
```

- `query-activities` (always **without** `-p`) → the match list: one `pkg/activity` per line after the "N activities found" header. With a specific Open in app, filter the list client-side, so we can also say how many *other* apps match.
- `resolve-activity` (only for Any app) → the verdict: a single component = opens directly; `ResolverActivity`/`ChooserActivity` (any package) = chooser; the browser package = the App Link is not verified.

Row layout: 14 px round glyph (tone bg, `panel`-coloured symbol 9/800) · text (`textDim`, ellipsis) · [14 px app tile · label 11/600 `text` · activity mono 10 `textDim`] · flex · right link (11/600 `accent`) that opens the popover.

| state | glyph / tone | text | right link |
|---|---|---|---|
| checking | 10 px spinner (`textDim`, `adb-spin` 0.7 s) | "Checking which apps handle this link…" | — |
| Any app, one handler | ✓ `green` | "Opens directly in" + tile + label + activity | "All N matches ▾" if N > 1 |
| Any app, several handlers | ! `amber` | "Chooser appears · N apps handle this link" | "Show apps ▾" |
| Any app, http(s), app matches but resolves to the browser | ! `amber` | "Opens in Chrome · the App Link for <host> isn’t verified" (browser label from the resolved package) | "Show N matches ▾" |
| specific app handles it | ✓ `green` | "Handled by" + tile + label + activity | "All N matches ▾" if N > 1 |
| specific app doesn't, others do | ✕ `red`, text `red` | "<App label> doesn’t handle this link · N other app(s) do" (`app does` when N = 1) | "Show apps ▾" |
| nothing matches | ✕ `red`, text `red` | "Nothing on this device handles this link" | — |
| query failed | (hide the row) | — | — |

Row tooltip: "Checked with cmd package query-activities, without launching. Updates as you type."

**Popover** (`JBPopup`, anchored under the row, full row width, no extra dialog height): header mono 10 `textDim` "query-activities · N matches for VIEW + BROWSABLE" + 1 px divider; one row per match: 16 px tile · label 11.5/600 · `pkg/.Activity` mono 10 `textDim` · tag "opens" (Any app) or "selected" (specific app) on the resolved/selected row (10/600 `green`, 1 px `green` 40 % border, r3) · link "Open in this app" (sets Open in, closes the popover; hidden on the row that already is Open in).

### 2a. Query parameters (unchanged)

Key/value editor for the URI query string, under the handlers row.

- Label row: "QUERY PARAMETERS" + right link "+ Add parameter" (11/600 `accent`).
- Empty: dashed `border` box, r6, padding 8/10, 11 `textDim`: "No parameters. Add one, or type `?key=value` in the URI."
- Rows: box `border` r6, bg `bg`, padding 0/6/6. Header "Key · Value" 10.5/600 `textDim`. Grid `1fr · 10px "=" · 1.6fr · 22px ×`, gap 6/4, fields h 26 mono 11.5 (key 600).
- Hint: "Values are URL-encoded and kept in sync with the URI above."
- Two-way sync, URI string is the single source of truth: parse `?…` up to `#`, split `&` then first `=`, `URLDecoder` semantics; rebuild with percent-encoding (`%20` for space) leaving `,` `:` `/` readable; skip empty keys; keep `#fragment`; duplicates allowed, order kept.

### 3. Open in

- Combo-style field, h 36, r4, `field` bg, 1 px `borderStrong` (`accent` while open): 16 px tile · label 11.5/600 · second line mono 10 `textDim` (package, or "System chooser" for Any app) · caret.
- Popup with filter "Filter installed apps…", list max 180, selected row `accentBg`, "last used" mono 10 `textDim` on the remembered package.
- Options: **Any app** first + packages installed on the selected device, sorted by label.
- Remembered per application: `PropertiesComponent` `adbToolbox.deepLink.lastPackage`. Not installed on this device → show Any app, keep the stored value.

### 3a. App Link warning (handoff 7)

Shown when Open in is a **specific app** and the URI scheme is `http` or `https`. Under the Open in field, margin-top 6.

- Box: padding 6/9, r5, bg `amberBg`, 1 px `amber` at 33 % alpha. Flex row, gap 12, baseline.
- Text (10.5, line-height 1.45, `text`, wraps, max 2 lines at 600 px):
  "Open in <App label> delivers this link even if the App Link isn’t verified. On Android 12+ a real tap opens the browser unless <host> is verified."
- Link (11/600 `accent`, no wrap): **"Test as a real tap"** → sets Open in to Any app (and remembers it, like any pick). The warning disappears; the handlers row re-runs.
- Why: `-p <pkg>` targets the package directly and skips domain verification, so the launch "works" even when a user's tap would not reach the app.

### 4. Command (changed in handoff 7)

- Label row: "COMMAND" · flex · link **"Expand" / "Collapse"** · link **"Copy"** (both 11/600 `accent`, gap 12).
- **Collapsed (default):** one line, mono 11, `text` on `field`, 1 px `border`, r5, padding 7/9, `white-space: pre`, horizontal scroll. Never wraps.
- **Expanded:** one argument per line with ` \` continuation (same box, line-height 1.6). The choice is remembered (`adbToolbox.deepLink.commandExpanded`).
- Updates live. Copy always copies the single-line form; toast "Command copied".

### 5. Last launch result (handoff 7)

The dialog stays open, so the outcome of the last launch is shown **in the dialog**, between Command and the action row (margin-top 12).

**Toast decision:** launches started from the dialog (Open on device, ▸ in Recent) show **no toast** — the banner replaces it. Launches started outside the dialog (Favorites tile, Find Action) keep the toast: "Opened <uri> in <App label>", "Chooser opened for <uri>", or error toast "No app on this device handles <uri>".

**Busy:** while `am start -W` runs, the primary button reads "Opening…" (disabled) and the previous banner stays until the new result arrives.

**Banner:** flex row, gap 9, padding 9/8/9/10, r6, bg = tone bg (`greenBg` / `amberBg` / `redBg`), 1 px tone colour at 40 % alpha.
- Left: 16 px round glyph (✓ / ! / ✕), then an 18 px app tile when an app is known.
- Middle (flex, min-width 0): title 11.5/600 `text` · detail 11 / 1.45 `textDim` (wraps) · optional mono 10.5 `textDim` line (ellipsis, full value in tooltip).
- Right column (right-aligned, gap 4): "Last launch · 12:41" mono 10 `textDim` + × "Dismiss" · optional action link (11/600 `accent`) · "Copy output" (10.5 `textDim`, underlined) — copies the raw `am start -W` stdout+stderr.
- The banner clears when the user edits URI, params or Open in, or presses ×. Loading a Recent row clears it; ▸ replaces it with the new result.

**Parsing `am start -W`:** read `Status:`, `LaunchState:`, `Activity:`, `TotalTime:`, `Warning:`, `Error:` lines.

| state | detected by | tone | title | detail | mono line | action |
|---|---|---|---|---|---|---|
| success | `Status: ok`, `Activity:` is a normal app, no `Warning:` | green ✓ | "Opened in <App label of Activity pkg>" | "Cold start · 412 ms" / "Warm start · 180 ms" / "Hot start · 90 ms" (from `LaunchState`, `TotalTime`); with Any app append " · Android picked this app" | `<pkg>/<.Activity>` | — |
| success in a different app (Any app, http(s), resolved to the browser) | `Activity:` package ≠ the app(s) matched in §2b, and is a browser | amber ! | "Opened in <Browser>, not in <App>" | "The App Link for <host> isn’t verified on this device, so the tap went to the browser." | `<pkg>/<Activity>` | **"Copy verify command"** → copies `adb -s <serial> shell pm get-app-links <app pkg>`, toast "Copied: pm get-app-links <pkg>" |
| chooser shown | `Activity:` class ends with `ResolverActivity` or `ChooserActivity` | amber ! | "The system chooser opened on the device" | http(s): "The App Link for <host> isn’t verified, so Android asks which app to use. Pick one on the device." · otherwise: "N apps handle this link. Pick one on the device, or choose the app in Open in." | `Activity: <component>` | — |
| delivered to running activity | `Warning: Activity not started, intent has been delivered to currently running top-most instance.` | amber ! | "Delivered to the running activity" | "<App label> was already on top and got the link in onNewIntent(). Nothing new was launched." | `<pkg>/<.Activity>` | **"Turn on Cold start"** (hidden when already on) → turns the toggle on (§6) |
| no handler | `Error: Activity not started, unable to resolve Intent` | red ✕ | "No app on this device handles this link" | specific app: "<App label> has no activity for this link with VIEW + BROWSABLE." · Any app: "Nothing matches VIEW + BROWSABLE for <scheme>://<host>. Check the scheme and host in the app’s intent filter." | `Error: Activity not started, unable to resolve Intent` | — |
| other adb error | non-zero exit, or any other `Error:` / `error:` line | red ✕ | "Couldn’t open the link" | "adb returned an error. Nothing was launched." | first error line, e.g. `error: device '49060DLAQ002W7' not found` | — |

Replaces the handoff 6 rule "red text under the URI" for launch failures.

History: every state except **no handler** and **other adb error** is saved/moved to the newest position.

### 6. Actions (changed in handoff 7)

One row, margin-top 14, gap 6, wraps if needed:

- Left: **Cold start** checkbox — 13×13 r3 box (`field` + `borderStrong`; checked = `accent` fill + white tick) + label 11 `text`. Tooltip: "Force-stop the app before opening the link (am start -S)". Remembered per application: `adbToolbox.deepLink.coldStart`. Adds `-S` to the command preview and the launch.
- Flex spacer.
- "Clear form" — text button 11 `textDim`; resets URI, params and result; Open in → remembered default; Cold start keeps its value.
- **"Close"** (secondary; was "Cancel" — nothing is cancelled because launches don't close the dialog).
- **"Open on device"** (primary, default button) → "Opening…" while running.

## Command (changed in handoff 7)

Still fixed — only the link, the package and Cold start vary.

```
adb -s <serial> shell am start \
    -W \
    [-S] \
    -a android.intent.action.VIEW \
    -c android.intent.category.BROWSABLE \
    -d '<uri>' \
    [-p <package>]
```

- `-S` only when Cold start is on. With Any app, `am` force-stops the resolved target before launching.
- `-p <package>` (was a bare trailing package) only when Open in is a specific app.
- `-c BROWSABLE` → only activities reachable from a link tap match.
- Known differences from a real tap (deep-link audit, 2026-10-29): a link in a TextView (SMS, e-mail) is sent without
  BROWSABLE, so this is stricter; `am start` always adds `FLAG_ACTIVITY_NEW_TASK`, so a Navigation back stack is the
  "new task" variant; with `-p` an `https` link reaches the app even when its App Link is not verified (§3a).
- Quote the URI for the device shell: single quotes, inner `'` → `'\''`.
- Run off the EDT (`Task.Backgroundable` or coroutine on `Dispatchers.IO`).

## History

- Per application, `PersistentStateComponent` → `adbToolbox.deepLink.history`: list of `{ uri, pkg, lastRun: epochMillis, handledBy }`.
- Max **50**. Stored newest first; **displayed oldest → newest** (newest at the bottom). Re-running an existing `uri + pkg` pair moves it to the newest position; same URI with a different package is a separate entry.
- Not device-specific.

## Persisted settings (summary)

| key | value |
|---|---|
| `adbToolbox.deepLink.lastPackage` | last Open in choice (`""` = Any app) |
| `adbToolbox.deepLink.coldStart` | `true` / `false` (handoff 7) |
| `adbToolbox.deepLink.commandExpanded` | `true` / `false` (handoff 7) |
| `adbToolbox.deepLink.history` | see History |

## 10. Screenshots (handoff 7)

All in `screenshots/deep-link-h7/`, dialog shown at 70 % or 64 % scale so it fits one image.

| file | state |
|---|---|
| `dl-01-success-dark.png` | specific app, handlers row "Handled by", success banner (cold start) |
| `dl-02-onnewintent-dark.png` | same link again → "Delivered to the running activity" + "Turn on Cold start" |
| `dl-03-chooser-handlers-popover-dark.png` | Any app + custom scheme with 2 handlers: chooser row, popover open, chooser banner |
| `dl-04-https-any-app-browser-dark.png` | Any app + https: "Opens in Chrome · …isn’t verified" row, "Opened in Chrome, not in Acme Shop" banner |
| `dl-05-https-specific-app-warning-dark.png` | specific app + https: App Link warning with "Test as a real tap" |
| `dl-06-no-handler-dark.png` | unknown scheme: red handlers row + "No app on this device handles this link" banner |
| `dl-07-checking-dark.png` | handlers row while checking |
| `dl-08-adb-error-dark.png` | "Couldn’t open the link" with the adb error line |
| `dl-09-success-cold-start-light.png` | Light, Cold start on (`-S` in the preview) |
| `dl-10-https-specific-app-warning-light.png` | Light, App Link warning + success banner |

## Definition of done

1. Button disabled with reason tooltip when no device is online.
2. Undecorated modal `DialogWrapper` at 600 px with in-body title and ×; independent of tool-window width.
3. Recent: oldest at the top, newest at the bottom, scrolled to the newest on open and after each launch; 50 entries open instantly.
4. Row click loads, ▸ loads + runs (dialog stays open), × removes; loaded row highlighted.
5. Open in and Cold start are remembered across IDE restarts.
6. Query parameters: two-way sync without cursor jumps; `a b&c` → `a%20b%26c`; `#fragment` survives.
7. Command preview: one line by default, never breaks inside a token; Expand shows one argument per line; `-S` and `-p <pkg>` appear exactly when set; Copy gives a single line that runs as-is.
8. Handlers row: appears ≤ 500 ms after typing stops, never launches, drops stale responses, all seven states reproducible (§2b).
9. Every `am start -W` outcome in §5 maps to the right banner, including chooser, onNewIntent and browser fallback; no toast for in-dialog launches.
10. App Link warning shows only for specific app + http(s); "Test as a real tap" switches to Any app.
11. In the common states (Recent shown, one handler, success banner) the dialog is no taller than in handoff 6.
12. URI with `&` and `'` reaches the app unchanged (`adb logcat | grep -i intent`).
13. Dark and Light match the screenshots.
