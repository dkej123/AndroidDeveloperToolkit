package dev.acme.adbtoolbox.intellij.host

import com.intellij.ui.components.JBPanel
import java.awt.CardLayout
import javax.swing.JComponent

/**
 * The "active view" named slot (task 010, `design/README.md`'s Global layout). A generic
 * add-but-hide registry (`CardLayout`) rather than a per-feature `when`/switch: each later
 * per-feature task calls [registerFeatureView] from its own file, so this class never grows a
 * hardcoded list of feature names. [registerFeatureView] is idempotent — the first call for a
 * given [routeKey] constructs and mounts the component; every later call returns that same
 * mounted instance — which is what keeps a feature view's scroll position/filters/state alive
 * across navigation (`design/IMPLEMENTATION.md` §3 "view host ... keep instances alive").
 */
class FeatureViewHost : JBPanel<FeatureViewHost>(CardLayout()) {

    private val registeredViews = mutableMapOf<String, JComponent>()

    fun isRegistered(routeKey: String): Boolean = routeKey in registeredViews

    fun componentFor(routeKey: String): JComponent? = registeredViews[routeKey]

    /**
     * Registers [routeKey]'s view, constructing it via [componentProvider] only the first time —
     * later calls (e.g. a feature re-registering itself defensively) return the already-mounted
     * instance untouched. This is the registration seam later feature tasks build against.
     */
    fun registerFeatureView(routeKey: String, componentProvider: () -> JComponent): JComponent =
        registeredViews.getOrPut(routeKey) {
            componentProvider().also { add(it, routeKey) }
        }

    fun show(routeKey: String) {
        checkNotNull(registeredViews[routeKey]) { "No feature view registered for route key '$routeKey'" }
        (layout as CardLayout).show(this, routeKey)
    }
}
