package dev.acme.adbtoolbox.intellij.host

import javax.swing.JComponent
import javax.swing.JLayeredPane

/**
 * The bounded "overlays" named slot (task 010): later tasks (dialogs, confirmations, toasts —
 * `design/IMPLEMENTATION.md` §3 "toasts | non-modal custom overlay in the tool window's
 * `JLayeredPane`") show/dismiss components here rather than adding to arbitrary parents. Every
 * overlay this layer still owns is torn down by [dismissAll] on host disposal, even if a caller
 * never explicitly dismissed it — no overlay may outlive the host.
 */
class OverlayLayer(private val target: JLayeredPane) {

    private val activeOverlays = mutableListOf<JComponent>()

    val overlayCount: Int get() = activeOverlays.size

    fun isShowing(overlay: JComponent): Boolean = overlay in activeOverlays

    fun show(overlay: JComponent) {
        if (overlay in activeOverlays) return
        activeOverlays += overlay
        target.add(overlay, JLayeredPane.POPUP_LAYER as Any)
        target.revalidate()
        target.repaint()
    }

    fun dismiss(overlay: JComponent) {
        if (!activeOverlays.remove(overlay)) return
        target.remove(overlay)
        target.revalidate()
        target.repaint()
    }

    fun dismissAll() {
        activeOverlays.toList().forEach(::dismiss)
    }
}
