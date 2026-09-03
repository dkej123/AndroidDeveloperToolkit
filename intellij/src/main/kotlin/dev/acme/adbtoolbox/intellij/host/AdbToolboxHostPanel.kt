package dev.acme.adbtoolbox.intellij.host

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLayeredPane

/**
 * The neutral root Swing host (task 010): named slots for device context, navigation, active
 * view, and feedback (`design/README.md`'s Global layout — device bar, rail, active view, status
 * bar), plus a bounded [overlays] layer above all of them. Purely structural/mounting
 * infrastructure — no color, font, spacing, icon, or dimension is set here; those are task 042+.
 *
 * A [JLayeredPane] rather than a plain [JBPanel] so [overlays] has somewhere to sit above the rest
 * of the content (`design/IMPLEMENTATION.md` §3: toasts/popups live in the tool window's
 * `JLayeredPane`). [contentPanel] always fills the full bounds; overlay components position
 * themselves when shown.
 */
class AdbToolboxHostPanel : JLayeredPane(), Disposable {

    val deviceContextSlot: JBPanel<*> = JBPanel<Nothing>(BorderLayout())
    val navigationSlot: JBPanel<*> = JBPanel<Nothing>(BorderLayout())
    val feedbackSlot: JBPanel<*> = JBPanel<Nothing>(BorderLayout())
    val activeViewHost: FeatureViewHost = FeatureViewHost()

    val overlays: OverlayLayer = OverlayLayer(this)

    private val presentationSignals = HostPresentationSignals(this)
    val hostWidth get() = presentationSignals.width
    val themeChanges get() = presentationSignals.themeChanges

    private val contentPanel: JBPanel<*> = JBPanel<Nothing>(BorderLayout()).apply {
        add(deviceContextSlot, BorderLayout.NORTH)
        add(navigationSlot, BorderLayout.WEST)
        add(activeViewHost, BorderLayout.CENTER)
        add(feedbackSlot, BorderLayout.SOUTH)
    }

    init {
        layout = null
        add(contentPanel, DEFAULT_LAYER as Any)
        presentationSignals.attach()
    }

    override fun doLayout() {
        contentPanel.setBounds(0, 0, width, height)
    }

    /** The feature-view registration seam: see [FeatureViewHost.registerFeatureView]. */
    fun registerFeatureView(routeKey: String, componentProvider: () -> JComponent): JComponent =
        activeViewHost.registerFeatureView(routeKey, componentProvider)

    fun showFeatureView(routeKey: String) = activeViewHost.show(routeKey)

    override fun dispose() {
        overlays.dismissAll()
        presentationSignals.detach()
    }
}
