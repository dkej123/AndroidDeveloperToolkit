package dev.acme.adbtoolbox.intellij.host

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Raw platform presentation inputs (task 010): actual host width and theme-change events, exposed
 * as observable signals for a later presentation-layer task (051, responsive/scaling) to consume.
 * This class deliberately makes no visual decision itself — no breakpoint, color, dimension, or
 * font is chosen here, only the two raw signals `design/README.md`'s "Responsive rules" and theme
 * support are measured against.
 *
 * [resizeListener] and [lafListener] are `internal` — not private — purely so platform tests can
 * invoke them directly to simulate a resize/theme-change without depending on real AWT event-queue
 * delivery or an actual Look and Feel switch inside the headless test sandbox.
 */
class HostPresentationSignals(private val hostComponent: JComponent) {

    private val _width = MutableStateFlow(hostComponent.width)
    val width: StateFlow<Int> = _width.asStateFlow()

    private val _themeChanges = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val themeChanges: SharedFlow<Unit> = _themeChanges.asSharedFlow()

    internal val resizeListener: ComponentAdapter = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            _width.value = (e.component as Component).width
        }
    }

    internal val lafListener = LafManagerListener { _themeChanges.tryEmit(Unit) }

    private var attached = false

    /** Test-only visibility hook: whether the theme listener is currently wired up. */
    internal val isThemeListenerAttached: Boolean get() = attached

    /**
     * Wires both listeners. This platform version's [LafManager] only exposes the plain
     * `addLafManagerListener(listener)`/`removeLafManagerListener(listener)` pair (no
     * `Disposable`-scoped overload), so [detach] removes it explicitly rather than relying on a
     * `Disposer` cascade — matching this module's established manual-cleanup `dispose()`
     * convention (`AdbToolboxProjectService`, `AdbToolboxToolWindowPanel`, task 007).
     */
    fun attach() {
        hostComponent.addComponentListener(resizeListener)
        LafManager.getInstance().addLafManagerListener(lafListener)
        attached = true
    }

    fun detach() {
        if (!attached) return
        hostComponent.removeComponentListener(resizeListener)
        LafManager.getInstance().removeLafManagerListener(lafListener)
        attached = false
    }
}
