package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolWindowReturnListenerTest : BasePlatformTestCase() {

    fun `test fires each time the tool window becomes active again, not while it stays active`() {
        var returns = 0
        val listener = ToolWindowReturnListener("ADB Toolbox") { returns++ }

        listener.onActiveToolWindow("ADB Toolbox")
        listener.onActiveToolWindow("ADB Toolbox")
        listener.onActiveToolWindow("Project")
        listener.onActiveToolWindow(null)
        listener.onActiveToolWindow("ADB Toolbox")

        assertEquals(2, returns)
    }
}
