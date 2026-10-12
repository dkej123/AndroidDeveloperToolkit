package io.github.dkej123.devicecockpit.intellij.settings

import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OpenDeviceCockpitSettingsActionTest : BasePlatformTestCase() {

    fun `test title action is available to the tool window composition`() {
        Class.forName("io.github.dkej123.devicecockpit.intellij.settings.OpenDeviceCockpitSettingsAction")
    }

    fun `test title action delegates to the shared settings opener`() {
        var openedProject = null as com.intellij.openapi.project.Project?
        val action = OpenDeviceCockpitSettingsAction(project) { openedProject = it }

        action.actionPerformed(TestActionEvent.createTestEvent(action))

        assertSame(project, openedProject)
    }

    fun `test title action update keeps settings available for a live project`() {
        val action = OpenDeviceCockpitSettingsAction(project) {}
        val event = TestActionEvent.createTestEvent(action)
        event.presentation.isEnabledAndVisible = false

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)
    }
}
