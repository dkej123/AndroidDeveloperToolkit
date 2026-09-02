# IMPLEMENTATION.md — IntelliJ Platform mapping

Target: Android Studio (and IntelliJ IDEA with the Android plugin), 2024.2+ / New UI.
Kotlin, Gradle IntelliJ Plugin 2.x, UI DSL v2 for forms, custom Swing components where the design
needs density the DSL cannot give (Logcat, rail, chips).

## 1. Module layout (suggested)

```
src/main/kotlin/dev/acme/adbtoolbox/
  AdbToolboxToolWindowFactory.kt    ToolWindowFactory, builds the root panel
  ui/RootPanel.kt                   title bar + device bar + rail + view host + status bar
  ui/DeviceBar.kt                   device selector, states, JBPopup picker
  ui/Rail.kt                        5 view buttons + badges + settings
  ui/views/DeviceView.kt            mirroring, capture, facts, reboot/shell/wake
  ui/views/AppsView.kt              package list + actions + confirmations
  ui/views/DisplayView.kt           font scale, density, quick toggles
  ui/views/NetworkView.kt           proxy form, recents
  ui/views/LogcatView.kt            toolbar, filters, virtualized log list
  ui/common/Chips.kt                PresetChipRow, LevelChip
  ui/common/Toasts.kt               non-modal toast host (bottom-left of the tool window)
  ui/common/Theme.kt                JBColor token objects mirroring tokens.json
  core/AdbService.kt                project service: device list, watching, command execution
  core/DeviceOverrides.kt           per-serial applied overrides + reset all
  core/LogcatSession.kt             adb logcat process, ring buffer, pause, filters
  core/Scrcpy.kt                    scrcpy discovery + process lifecycle
  settings/AdbToolboxSettings.kt    PersistentStateComponent (see README "State model")
resources/META-INF/plugin.xml
resources/icons/…                   copy from icons/
```

## 2. plugin.xml essentials

```xml
<idea-plugin>
  <id>dev.acme.adbtoolbox</id>
  <name>ADB Toolbox</name>
  <depends>com.intellij.modules.platform</depends>
  <depends optional="true" config-file="android.xml">org.jetbrains.android</depends>
  <extensions defaultExtensionNs="com.intellij">
    <toolWindow id="ADB Toolbox" anchor="right" secondary="false"
                icon="/icons/adbToolbox.svg"
                factoryClass="dev.acme.adbtoolbox.AdbToolboxToolWindowFactory"/>
    <projectService serviceImplementation="dev.acme.adbtoolbox.core.AdbService"/>
    <projectService serviceImplementation="dev.acme.adbtoolbox.settings.AdbToolboxSettings"/>
  </extensions>
  <actions>
    <action id="AdbToolbox.Mirror"      class="…MirrorAction"      text="Mirror Device">
      <keyboard-shortcut keymap="$default" first-keystroke="shift meta M"/></action>
    <action id="AdbToolbox.RestartApp"  class="…RestartAppAction"  text="Restart App">
      <keyboard-shortcut keymap="$default" first-keystroke="shift meta R"/></action>
    <action id="AdbToolbox.FocusLogcat" class="…FocusLogcatAction" text="Focus ADB Toolbox Logcat">
      <keyboard-shortcut keymap="$default" first-keystroke="shift meta L"/></action>
    <action id="AdbToolbox.Refresh"     class="…RefreshDevicesAction" text="Refresh Devices">
      <keyboard-shortcut keymap="$default" first-keystroke="meta shift D"/></action>
  </actions>
</idea-plugin>
```

If the Android plugin is present, prefer **ddmlib / AdbLibService** (`AndroidDebugBridge`) over
shelling out to `adb`, and fall back to the binary path from settings when it is not.

## 3. Component mapping

| Design element | Platform |
|---|---|
| tool window root | `SimpleToolWindowPanel(true, true)` or plain `JPanel(BorderLayout())` |
| title bar gear / more | tool window **title actions** (`ToolWindowEx.setTitleActions`) — do not draw your own |
| device bar | custom `JPanel` with `JBUI.Borders.customLine(bottom)`; background `UIUtil.getPanelBackground()`-equivalent token |
| device picker | `JBPopupFactory.createPopupChooserBuilder(devices)` with a custom renderer |
| rail | `JPanel(VerticalFlowLayout)` of `ActionButton`s (`Presentation.icon`, `toggleable`), or a `ContentManager` with `ToolWindow.setTabActions` |
| view host | `JBCardLayout` / `Wrapper`, one panel per view; keep instances alive so scroll and filters survive switching |
| sections | `panel { group(...) }` (UI DSL v2) with `JBUI.Borders.customLineBottom` |
| primary button | `JButton` + `putClientProperty("gotItButton")`-style default look, or `JButton().apply { isDefaultCapable = true }`; accent fill from theme key `Button.default.startBackground` |
| destructive button | outlined `JButton` with `foreground = red`, `border = customLine(red)`; confirm via `MessageDialogBuilder.yesNo(...).asWarning()` with **Cancel as default** |
| preset chips | custom `JComponent` row (paint rounded rect + label) or `JBOptionButton`-like segmented control; must support ← → arrow traversal |
| toggles | `JBCheckBox` styled as a switch, or `ThreeStateCheckBox`-style custom painter matching the 24×13 track |
| text fields | `JBTextField` with `emptyText`; validation via `ComponentValidator` (red border + inline message) |
| search fields | `SearchTextField` (gives the clear "×" and history for free) |
| app list | `JBList` + custom `ListCellRenderer` (34px rows) + `ListSpeedSearch`; filter with `SpeedSearchUtil` highlighting |
| Logcat body | `EditorTextField` in a console-like read-only editor **or** a virtualized `JBList`; use `ConsoleViewContentType`-style attributes for severity. Do **not** use a plain `JTextArea` — 1 482+ lines need virtualization |
| Logcat toolbar | `ActionToolbar` from a `DefaultActionGroup` of `ToggleAction`s (pause, autoscroll, wrap) + `AnAction` (clear) |
| paused pill | floating `JBLabel` in a `JLayeredPane` over the log, or `Notifications.Bus`-free custom overlay |
| toasts | non-modal custom overlay in the tool window's `JLayeredPane` (preferred, matches design) or `Notification` with `NotificationType` — never a modal dialog |
| status bar | custom 22px `JPanel` inside the tool window (not the IDE status bar) |
| tooltips | `HelpTooltip().setTitle(...).setDescription(...).setShortcut(...)` — carries the design's title/shortcut/consequence structure |
| disabled reason | `component.isEnabled = false` + `HelpTooltip` description "Connect a device to use this" |
| colors | `JBColor(light, dark)` constants in `ui/common/Theme.kt`, seeded from tokens.json |
| fonts | `JBUI.Fonts.label()`, `JBFont.small()`, `EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)` for mono |
| spacing | `JBUI.scale(n)` for **every** pixel value in this handoff (HiDPI) |

## 4. adb / scrcpy command reference

Run everything off the EDT (`AppExecutorUtil.getAppScheduledExecutorService()` or coroutines);
report failures as error toasts with a fix action. `$S` = selected serial.

```
devices            adb devices -l                      → model, serial, transport, state (device|unauthorized|offline)
wireless pair      adb pair <host:port> <code>  /  adb connect <host:port>

mirroring          scrcpy -s $S [--stay-awake --show-touches --max-size 1920 --video-bit-rate 8M]
screenshot         adb -s $S exec-out screencap -p > <captureDir>/screen-<ts>.png
record start       adb -s $S shell screenrecord /sdcard/rec-<ts>.mp4      (3 min cap)
record stop        SIGINT the process, then adb -s $S pull … && adb -s $S shell rm …

packages           adb -s $S shell pm list packages -3        (add nothing for user apps; -s for system)
app label          adb -s $S shell dumpsys package <pkg> | grep -m1 versionName  (label via aapt/PackageManager)
debuggable         adb -s $S shell pm dump <pkg> | grep -m1 FLAG_DEBUGGABLE
force-stop         adb -s $S shell am force-stop <pkg>
launch             adb -s $S shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1
restart            force-stop, then launch
clear data         adb -s $S shell pm clear <pkg>             ← confirm first
uninstall          adb -s $S uninstall <pkg>                  ← confirm first

font scale         adb -s $S shell settings put system font_scale <0.25..5>
font reset         adb -s $S shell settings put system font_scale 1.0
density            adb -s $S shell wm density <dpi>            (dpi = round(physical * pct/100))
density reset      adb -s $S shell wm density reset
physical density   adb -s $S shell wm density                  → "Physical density: 428"
dark mode          adb -s $S shell cmd uimode night yes|no
animations off     adb -s $S shell settings put global window_animation_scale 0   (+ transition_animation_scale, animator_duration_scale)
show touches       adb -s $S shell settings put system show_touches 0|1

proxy set          adb -s $S shell settings put global http_proxy <host>:<port>
proxy reset        adb -s $S shell settings put global http_proxy :0
proxy read         adb -s $S shell settings get global http_proxy
computer IP        resolve the primary non-loopback IPv4 of the active interface (en0 / eth0) in JVM code, not via adb

logcat             adb -s $S logcat -v threadtime            (filter client-side; keep a ring buffer)
logcat by pkg      adb -s $S shell pidof <pkg>  → filter by pid (survives restarts by re-resolving)
clear buffer       clear the local ring buffer only — the design's Clear does NOT run "logcat -c"

facts              adb -s $S shell getprop ro.build.version.release / ro.build.version.sdk / ro.product.model /
                   ro.product.cpu.abi ; adb -s $S shell wm size ; adb -s $S shell dumpsys battery ;
                   adb -s $S shell uptime
```

Notes for correctness:
- Density and font scale must be **read back** after applying so the amber override badge reflects
  device truth, not the last click.
- Track applied overrides per serial in `DeviceOverrides` so "N overrides · reset all" is accurate
  after an IDE restart and after reconnects.
- `scrcpy` and `adb` discovery: settings path → `ANDROID_HOME/platform-tools` → `PATH`.
  Missing binary is an error toast with **Set path…** opening the plugin settings, not a dialog.
- The proxy applies device-wide and survives reboot; the plugin must offer Reset and warn about
  certificate pinning (copy is in the README).

## 5. Definition of done

1. Five views reachable from the rail; device bar and status bar pinned and always visible.
2. All four device states render as specified, including the amber unauthorized banner.
3. Every device-mutating control is disabled at 45% with a reason tooltip when no device is online.
4. Font scale and density: presets, custom with validation, reset, amber override badge on the rail,
   and "N overrides · reset all" in the status bar.
5. Proxy: validation, "Use my computer IP", enable/disable, active banner, recents.
6. Logcat: severity coloring per tokens.json, search with highlighting, level and package filters,
   pause, autoscroll with the paused pill, wrap, clear, virtualized rendering of 10k+ lines.
7. scrcpy start/stop with a status-bar chip; screenshot and recording with the elapsed chip and a
   Reveal action on the result toast.
8. Uninstall and clear data confirm with Cancel as the default; nothing else uses a modal.
9. Works in New UI Dark and IntelliJ Light through JBColor, at 100% and 200% scaling, down to 300px wide.
10. Keyboard model from the README works, including Space (pause) and End (resume autoscroll).
