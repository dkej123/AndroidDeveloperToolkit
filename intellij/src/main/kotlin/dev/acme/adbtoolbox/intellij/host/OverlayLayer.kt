package dev.acme.adbtoolbox.intellij.host

import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLayeredPane

/**
 * The bounded "overlays" named slot (task 010): later tasks (dialogs, confirmations, toasts —
 * `design/IMPLEMENTATION.md` §3 "toasts | non-modal custom overlay in the tool window's
 * `JLayeredPane`") show/dismiss components here rather than adding to arbitrary parents. Every
 * overlay this layer still owns is torn down by [dismissAll] on host disposal, even if a caller
 * never explicitly dismissed it — no overlay may outlive the host.
 *
 * [show]'s optional `layoutBounds` (task 043) computes one overlay's bounds from the current
 * target size — e.g. the device picker's `top:62,left:8,right:8` placement and the toast stack's
 * bottom-left anchor above the status bar (`design/README.md` §1/Global layout). It is re-applied
 * on every [show] call (a coordinator calls [show] again after each state update, once the
 * overlay's own content/preferred size may have changed) and on [relayout] (called from
 * [AdbToolboxHostPanel.doLayout] on every host resize). Overlays with no `layoutBounds` are left at
 * whatever bounds they already have — task 010's original, still-supported behavior.
 */
class OverlayLayer(private val target: JLayeredPane) {

    private val activeOverlays = mutableListOf<JComponent>()
    private val layoutFns = mutableMapOf<JComponent, (width: Int, height: Int) -> Rectangle>()

    val overlayCount: Int get() = activeOverlays.size

    fun isShowing(overlay: JComponent): Boolean = overlay in activeOverlays

    fun show(overlay: JComponent, layoutBounds: ((width: Int, height: Int) -> Rectangle)? = null) {
        if (layoutBounds != null) layoutFns[overlay] = layoutBounds
        if (overlay in activeOverlays) {
            applyBounds(overlay)
            return
        }
        activeOverlays += overlay
        target.add(overlay, JLayeredPane.POPUP_LAYER as Any)
        applyBounds(overlay)
        target.revalidate()
        target.repaint()
    }

    fun dismiss(overlay: JComponent) {
        if (!activeOverlays.remove(overlay)) return
        layoutFns.remove(overlay)
        target.remove(overlay)
        target.revalidate()
        target.repaint()
    }

    fun dismissAll() {
        activeOverlays.toList().forEach(::dismiss)
    }

    /** Re-applies every overlay's `layoutBounds` against the target's current size (host resize). */
    fun relayout() {
        activeOverlays.forEach(::applyBounds)
    }

    private fun applyBounds(overlay: JComponent) {
        val layoutBounds = layoutFns[overlay] ?: return
        overlay.bounds = layoutBounds(target.width, target.height)
    }
}
