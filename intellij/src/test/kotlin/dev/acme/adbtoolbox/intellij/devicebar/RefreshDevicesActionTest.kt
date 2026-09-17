package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Task 050's global refresh shortcut/action: [RefreshDevicesAction] must forward the exact same
 * [dev.acme.adbtoolbox.application.devicebar.DeviceBarIntent.Refresh] the device bar's refresh icon
 * button ([DeviceContextBarPanel]) sends, reached through the same shared
 * [AdbToolboxProjectService.deviceBarViewModel] instance, mirroring
 * [dev.acme.adbtoolbox.intellij.apps.RestartAppActionTest]'s established shape.
 */
class RefreshDevicesActionTest : BasePlatformTestCase() {

    fun `test invoking the action forwards Refresh to the shared device bar view model`() = runBlocking {
        val composition = project.service<AdbToolboxProjectService>()
        val action = RefreshDevicesAction()
        val event = TestActionEvent.createTestEvent(action) { key ->
            if (key === CommonDataKeys.PROJECT.name) project else null
        }

        // Started before the action fires so it observes the isRefreshing=true transition
        // DeviceBarViewModel.refresh() sets from inside its launched coroutine — the only externally
        // visible proof (short of mocking the shared singleton) that the click/shortcut path and this
        // action both drive the exact same DeviceBarViewModel instance.
        val sawRefreshing = async { composition.deviceBarViewModel.state.first { it.isRefreshing } }

        action.actionPerformed(event)

        withTimeout(5_000) { sawRefreshing.await() }
    }

    fun `test update enables the action only when a project is available`() {
        val action = RefreshDevicesAction()
        val withProject = TestActionEvent.createTestEvent(action) { key ->
            if (key === CommonDataKeys.PROJECT.name) project else null
        }
        val withoutProject = TestActionEvent.createTestEvent(action) { null }

        action.update(withProject)
        action.update(withoutProject)

        assertTrue(withProject.presentation.isEnabledAndVisible)
        assertFalse(withoutProject.presentation.isEnabledAndVisible)
    }

    fun `test the action declares a background update thread, never blocking the EDT on policy checks`() {
        val action = RefreshDevicesAction()

        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }
}
