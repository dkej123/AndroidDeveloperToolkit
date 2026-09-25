package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * Calls [onReturn] whenever the user comes back to the tool window [toolWindowId] (it becomes the
 * active tool window after something else was active) — the moment to re-read device state that
 * may have changed meanwhile, e.g. an app installed by Run.
 */
internal class ToolWindowReturnListener(
    private val toolWindowId: String,
    private val onReturn: () -> Unit,
) : ToolWindowManagerListener {
    private var wasActive = false

    override fun stateChanged(toolWindowManager: ToolWindowManager) {
        onActiveToolWindow(toolWindowManager.activeToolWindowId)
    }

    internal fun onActiveToolWindow(activeId: String?) {
        val active = activeId == toolWindowId
        if (active && !wasActive) onReturn()
        wasActive = active
    }
}
