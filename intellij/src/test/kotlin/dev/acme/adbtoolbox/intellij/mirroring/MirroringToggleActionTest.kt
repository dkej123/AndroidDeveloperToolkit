package dev.acme.adbtoolbox.intellij.mirroring

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.mirroring.MirroringIntent
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService

/**
 * Task 018's global keyboard-shortcut action: [MirroringToggleAction] must forward the exact same
 * [MirroringIntent.Toggle] the Device-view button
 * ([dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringView]) sends, reached through the same
 * shared [AdbToolboxProjectService.mirroringViewModel] instance — never a second, independent
 * ViewModel/session — so the two triggers can never diverge. No device is selected in this headless
 * `:intellij:test` sandbox, so [dev.acme.adbtoolbox.application.mirroring.MirroringViewModel.handle]
 * takes its "no eligible device" branch — enough to prove the action reaches the real, shared
 * ViewModel (and thus the same code path the button uses) without needing a real/fake device.
 */
// BasePlatformTestCase reuses the same light project — and therefore the same cached
// project.service<AdbToolboxProjectService>() singleton — across every test method in a class
// (documented directly in AdbToolboxProjectServiceTest), so tests that invoke the action against
// that shared composition assert *deltas* against its feedback channel rather than exact/singleton
// contents, to stay independent of whatever other test methods in this class already ran.
class MirroringToggleActionTest : BasePlatformTestCase() {

    fun `test invoking the action forwards Toggle to the shared mirroring view model`() {
        val composition = project.service<AdbToolboxProjectService>()
        val before = composition.feedbackViewModel.state.value.toasts.size
        val action = MirroringToggleAction()
        val event = TestActionEvent.createTestEvent(action) { key ->
            if (key === CommonDataKeys.PROJECT.name) project else null
        }

        action.actionPerformed(event)

        val toasts = composition.feedbackViewModel.state.value.toasts
        assertEquals(before + 1, toasts.size)
        val toast = toasts.last()
        assertEquals(FeedbackSeverity.Warning, toast.severity)
        assertEquals("No eligible device selected", toast.text)
    }

    fun `test update enables the action only when a project is available`() {
        val action = MirroringToggleAction()
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
        val action = MirroringToggleAction()

        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun `test a click through the real Device-view button and the action both reach the same view model instance`() {
        val composition = project.service<AdbToolboxProjectService>()
        val before = composition.feedbackViewModel.state.value.toasts.size
        val action = MirroringToggleAction()
        val event = TestActionEvent.createTestEvent(action) { key ->
            if (key === CommonDataKeys.PROJECT.name) project else null
        }

        action.actionPerformed(event)
        composition.mirroringViewModel.handle(MirroringIntent.Toggle)

        // Both calls landed on the exact same shared instance/feedback channel — two more "no
        // eligible device" warnings, never two independently-tracked mirroring sessions.
        assertEquals(before + 2, composition.feedbackViewModel.state.value.toasts.size)
    }
}
