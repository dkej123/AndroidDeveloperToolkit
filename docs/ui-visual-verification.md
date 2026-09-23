# UI and visual verification

Last reviewed: 2026-09-23

This is the pre-installation UI gate for the production Swing implementation. The visual test
renders real plugin components in the IntelliJ test sandbox and compares them with reviewed PNGs;
the journey tests exercise controls independently of pixel output.

## Reviewed design matrix

| Golden | View | Theme | Width class | Design state |
|---|---|---|---|---|
| `device-empty-light-narrow.png` | Device | Light | 300px narrow | no-device guidance and actions |
| `device-connected-dark-dock.png` | Device | Dark | 380px dock | mirroring, capture, facts and device actions (real mounted sub-views) |
| `apps-dark-dock.png` | Apps | Dark | 380px dock | filtered list, selected app, lifecycle actions |
| `display-light-dock.png` | Display | Light | 380px dock | active font/density overrides and toggles |
| `network-dark-dock.png` | Network | Dark | 380px dock | active proxy, warning and recents |
| `logcat-dark-wide.png` | Logcat | Dark | 560px wide | query, severity rows, paused stream and footer |

Every image also includes the production 30px device bar, 34px navigation rail and 22px status
bar. Together the matrix covers all five primary views, both supplied palettes, and each responsive
width class. The source of truth used for review is `design/designs/ADB Toolbox Plugin.dc.html`,
`design/IMPLEMENTATION.md`, and `design/tokens/tokens.json`.

## Defects found and fixed during review

### Side-by-side design comparison (second pass)

Each golden was compared side by side with the prototype rendered in the same state, theme and
width (headless Chromium over `design/designs/ADB Toolbox Plugin.dc.html`). The first set of
goldens had been approved while still diverging from the design; this pass fixed:

- The headless sandbox runs Metal, whose default control font is bold, and body/caption
  typography inherited that style, so all body copy rendered bold. Body and caption are now
  explicitly regular (tokens: weight 400) and the harness uses IntelliJ's regular label font.
- Device bar and status bar content sat at the top of their fixed rows (`FlowLayout` with no
  vertical centering); both now use a vertically centered flex row. The device bar gained the
  selector caret, the design's chip styling, regular-weight no-device/loading copy, and a long
  device name now ellipsises instead of running under the refresh action.
- The rail painted full-width selection, had no top padding, and did not pin Settings to the
  bottom. Buttons are now 26px, centered, 2px apart, and Settings is pinned to the rail bottom.
- Text buttons were plain Swing buttons with icons the design does not have. Primary, secondary,
  destructive and link roles now share one painted `DesignButton` (rounded 5px, supplied heights,
  45% disabled opacity).
- Apps: stretched tiles and debug tags, a square full-width selection, missing search
  placeholder, a centered selected-app label, a missing Launch button and a destructive label
  overlapping Clear data.
- Display and Network: help text was ellipsised and right-aligned instead of wrapping, the
  Network primary action (Enable/Disable) was not visible, and recent targets were centered.
- Logcat: the paused pill was painted underneath the log body, the package filter chip was
  truncated to "…", inactive level chips had borders and severity colors, rows had no 8px inset or
  fixed 16px line height, and messages did not start on a common column.
- Device: the connected Device view was not covered by any golden. Its sections stretched to fill
  the height, content overflowed the viewport (section meta and the third facts column were cut),
  help text did not wrap, and the empty state's device glyph was stretched into a wide box.
- Status bar: "1 overrides" is now "1 override", and the running-process chip has its dot,
  border and radius.

Known remaining differences are platform or data gaps, not layout defects: IntelliJ's label font
is larger than the prototype's 11.5px browser font; `AllIcons` resolve to empty placeholders in
the test sandbox, so the Settings gear is absent from the images; the recent-proxy notes
("mitmproxy", "Charles") and the status-bar "adb 35.0.2" version are not present in the view
state; and the empty state's "adb 35.0.2 · /opt/homebrew/bin/adb" footer is still a fixed string.

### First pass

- The global device, navigation and status regions were taking Swing defaults instead of their
  specified fixed dimensions.
- The online device selector repeated the serial already shown next to the model.
- App-list renderers painted selection but gave nested labels zero-sized bounds.
- Font-scale and density chips disappeared or overlapped later sections instead of wrapping at dock
  width.
- Display and Network forms could expose horizontal scrolling rather than tracking the viewport.
- A long active-proxy message overlapped the Reset action.
- At 300px, the no-device copy and Wi-Fi pairing action were clipped.
- The icon catalog could cache null icons when IntelliJ's global loader was temporarily inactive,
  making the full suite order-dependent.
- The installable ZIP bundled its own `kotlinx-coroutines-core`, splitting `Dispatchers` between
  plugin and IDE classloaders and crashing tool-window initialization with `LinkageError`. The
  plugin now uses IntelliJ's copy, and the distribution gate rejects a future ZIP containing it.
- Device discovery inferred the Android SDK from IntelliJ's generic JDK table. It now asks
  Android Studio for the exact adb executable selected by the IDE, so its polling process talks to
  the same server as Device Manager before falling back to environment/PATH discovery.

Each geometry defect has a component-level regression assertion in addition to the reviewed image.

## Automated interaction coverage

`PreInstallUiJourneyTest` covers the highest-risk local UI journeys: app search/selection/restart,
custom display validation and toggle dispatch, invalid proxy protection plus active-proxy reset, and
Logcat query/severity/pause/wrap/clear/jump-to-latest controls. Existing feature suites continue to
cover navigation, unavailable-device policies, confirmation flows, view models and adapters.

## Running the gate

```bash
./gradlew :intellij:test --tests '*VisualRegressionTest' \
  --tests '*PreInstallUiJourneyTest' \
  --tests '*GlobalChromeLayoutTest'
./gradlew clean build koverVerify architectureCheck :intellij:verifyPluginDistribution
```

Only after a deliberate design review, refresh the references with:

```bash
./gradlew :intellij:test --tests '*VisualRegressionTest' -PupdateVisualGoldens=true
```

On a mismatch the test writes actual and diff PNGs under
`intellij/build/reports/visual`; CI uploads them as a failure artifact. The repository rule still
applies: plugin verification runs in CI, not locally.

## Remaining manual boundary

The deterministic gate does not replace a smoke test in a real Android Studio window with a
physical/emulated device, adb and scrcpy. Before publishing, install the produced ZIP in the
supported IDE, connect one online device, open every rail view, and smoke-test one non-destructive
action per view. Recording, screenshot output paths, external scrcpy launch and device-wide proxy
effects require that environment.

## Latest result

After the 2026-09-23 side-by-side design pass, all 492 IntelliJ integration/UI tests (including
the six-image visual gate and the new layout regressions) passed, together with `build`
(which includes `:intellij:verifyPluginDistribution`), `koverVerify` and `architectureCheck`.
The distribution ZIP was rebuilt by that run but not re-inspected by hash; re-run the gate above
and record the artifact checksum before publishing.
