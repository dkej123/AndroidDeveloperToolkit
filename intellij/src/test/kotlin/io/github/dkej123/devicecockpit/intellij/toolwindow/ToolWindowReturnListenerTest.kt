package io.github.dkej123.devicecockpit.intellij.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolWindowReturnListenerTest : BasePlatformTestCase() {

    fun `test fires each time the tool window becomes active again, not while it stays active`() {
        var returns = 0
        val listener = ToolWindowReturnListener(DeviceCockpitToolWindowFactory.TOOL_WINDOW_ID) { returns++ }

        listener.onActiveToolWindow(DeviceCockpitToolWindowFactory.TOOL_WINDOW_ID)
        listener.onActiveToolWindow(DeviceCockpitToolWindowFactory.TOOL_WINDOW_ID)
        listener.onActiveToolWindow("Project")
        listener.onActiveToolWindow(null)
        listener.onActiveToolWindow(DeviceCockpitToolWindowFactory.TOOL_WINDOW_ID)

        assertEquals(2, returns)
    }
}
