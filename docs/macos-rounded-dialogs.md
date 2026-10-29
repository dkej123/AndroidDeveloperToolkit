# Rounded, undecorated dialogs on macOS

How to show a `DialogWrapper` whose only visible frame is our own rounded surface (e.g. the
Device-view "Open deep link" dialog, `design/DEEP_LINK_DIALOG.md`) without platform artifacts.
Reference implementation: `intellij/.../deeplinks/DeepLinkWindowSurface.kt`.

## Symptoms we hit (Android Studio 2026.1, macOS, new UI)

1. An empty, title-bar-high strip (29 px) above the surface.
2. Square corners around the rounded surface: first a dark square frame (native shadow),
   then — with the shadow off — black squares in the four corners.

Unit tests did not catch any of this: they run headless and render only the Swing surface, never
the native window. Only the real IDE shows it.

## Root causes (measured, not guessed)

Found by logging the window and root-pane geometry when the dialog opened (`idea.log`):

| What the platform does | Effect |
|---|---|
| `DialogWrapperPeerImpl.show()` → `ComponentUtil.decorateWindowHeader(rootPane)` sets `apple.awt.fullWindowContent=true`, `apple.awt.transparentTitleBar=true` and a root-pane border with `top=29` — **even for an undecorated dialog** | the 29 px strip; a native titled-window background behind the surface |
| `DialogWrapperPeerImpl.MyDialog.show()` calls `setBackground(UIUtil.getPanelBackground())` on macOS (unless the IDE's own rounded-corners manager handles the window) — after anything we set in `init` | the window becomes opaque again: square corners |
| The native shadow is computed from the window's pixels when it first shows (still opaque then) | a square shadow frame |
| A per-pixel-transparent window still did not show the desktop through the corners; macOS drew them black | black corner squares |

Two dead ends worth not repeating:

- Removing the JBR custom title bar (`JBR.getWindowDecorations().setCustomTitleBar(dialog, null)`):
  `MyDialog.addNotify()` does install one, but window insets were already 0 — it was not the strip.
- Recomputing the shadow (`apple.awt.windowShadow.revalidateNow`) or turning it off
  (`Window.shadow=false`): only changed which artifact was visible.

## What works

All in `DeepLinkWindowSurface`, called from the dialog's `init` (`attach(dialog)`):

1. **Undo the title-bar decoration whenever it is applied**: a property-change listener on the root
   pane resets `apple.awt.fullWindowContent` / `apple.awt.transparentTitleBar` to `false` and any
   non-zero root-pane border to an empty one (`keepBorderless`). A one-time reset in `init` is not
   enough — `show()` applies it later.
2. **Keep the window transparent on every background change** (listener on the dialog's
   `background` property), not only once in `init` or `windowOpened`.
3. **Round the native window itself** to the surface radius:
   `JBR.getRoundedCornersManager().setRoundedCorners(dialog, radius)` (radius in points, `Float`,
   guarded by `JBR.isRoundedCornersManagerSupported()`). macOS then clips the corners and the native
   shadow follows the rounded shape. This is the step that actually removed the corner artifacts.
4. Keep the painted surface radius and the native radius in one place
   (`DeepLinkWindowSurface.cornerRadius()` / `cornerArc()`), so they cannot drift apart.

## Checklist for any new rounded dialog or window

- Prefer a plain `DialogWrapper` with the native title bar unless the design explicitly needs a
  custom frame; most artifacts come from fighting the platform.
- If it must be undecorated: reuse `DeepLinkWindowSurface` (or extract it to `ui/common` once a second
  dialog needs it) instead of re-implementing steps 1–4.
- Never rely on window transparency alone for rounded corners on macOS — round the native window.
- Verify in a real IDE on macOS (light and dark), not only in headless render tests. When something
  looks off, log `window.bounds`, `window.insets`, the root pane's insets / client properties and the
  component tree before changing code.
- In-panel rounded components (cards, toasts, buttons) have the related problem: never use a plain
  `JButton`/`JToggleButton` with the IDE look on top of a custom-painted surface — its own rounded
  fill shows square-ish corners against our background. Use `DesignButton`, `DesignIconButton`,
  `RoundedSurface` and paint non-opaque (see `ToastStackPanel`).
