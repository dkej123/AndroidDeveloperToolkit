# ADBHelper reference guide

## Source and legal boundary

- Local read-only checkout: `references/ADBHelper`
- Upstream: `https://github.com/classops/ADBHelper`
- Reviewed revision: `237d525cee8d55f26df7aa046e54cca7d5c26bf2`
- Bootstrap instructions: `references/README.md`

The upstream declares no license. Use it only to understand observable behavior, ddmlib integration,
and problem decomposition. Never copy or adapt code, parsers, strings, class names, resources, or UI.
Never modify or package the local checkout. Implement from this project's task, design, ADRs, public
Android/IntelliJ documentation, and tests.

## When to inspect it

Inspect only the relevant files before implementing these tasks:

| Tasks | Relevant local files | What to learn |
|---|---|---|
| 003, 005 | `ProjectManager.kt`, `receiver/ADBReceiver.kt`, `action/ADBAction.kt` | ddmlib bridge/device/shell/receiver flow and its limitations |
| 007 | `action/ADBAction.kt`, `DeviceChooserDialog.java`, `META-INF/plugin.xml` | IntelliJ action context and disposal integration only |
| 008, 009, 011 | `DeviceChooser.java`, `DeviceChooserDialog.java`, `DeviceChooserUtils.java`, `ProjectManager.kt` | bridge listeners, device states, serial persistence, picker interactions |
| 015 | `view/DeviceMessageDialog.kt` | which facts ddmlib exposes directly; not its UI |
| 021, 022 | `view/ActivityStackTreeDialog.kt` | package-list command/parsing and selection preservation |
| 023 | `action/GetAppStartTimeAction.kt` | default-activity resolution and launch behavior only |
| 024 | `action/ClearAppDataAction.kt`, `receiver/ClearAppDataReceiver.kt` | `pm clear` behavior and response shape only |

ADBHelper has no corresponding implementation for binary ADB, scrcpy, capture, display, network,
proxy, logcat, uninstall, Wi-Fi pairing, or this project's final ToolWindow design. Do not cite it as a
reference for those features.

## Useful concepts

- Keep device, project, and application context explicit at the action boundary.
- Treat ddmlib shell output as streamed data delivered by a receiver.
- Keep nontrivial `dumpsys` parsing separate from presentation.
- Preserve a chosen serial across refresh only when that exact device remains eligible.

## Patterns that must not be inherited

- synchronous or potentially blocking ADB work initiated on the EDT;
- implicit single/first-device selection or a lazily frozen device snapshot;
- raw command construction inside UI actions and unchecked shell interpolation;
- receivers with no cancellation, timeout, structured error, stderr, or truthful exit status;
- decoding each byte chunk independently as UTF-8;
- shell pipelines such as device-side `grep` and brittle one-format parsing;
- ddmlib, IntelliJ, facet, or `IDevice` types crossing into shared/application APIs;
- UI dialogs as the operation result model;
- implementation without fixture-based parser and fake-transport tests.

If the local checkout is unavailable, do not fetch it repeatedly during a task. Use the reviewed notes
above and continue from authoritative project requirements; bootstrap it once only when source-level
inspection is genuinely needed.
