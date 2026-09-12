package dev.acme.adbtoolbox.intellij.apps

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppLifecycleIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService

/**
 * Task 023's global restart shortcut/action: [RestartAppAction] must forward the exact same
 * [AppLifecycleIntent.Restart] the Apps-view Restart button
 * ([dev.acme.adbtoolbox.intellij.apps.AppsPanel]) sends, reached through the same shared
 * [AdbToolboxProjectService.appLifecycleViewModel] instance — never a second, independent
 * ViewModel/use-case — so the two triggers can never diverge, mirroring
 * [dev.acme.adbtoolbox.intellij.mirroring.MirroringToggleActionTest]'s established shape. No device
 * is selected in this headless `:intellij:test` sandbox, so
 * [dev.acme.adbtoolbox.application.apps.AppLifecycleViewModel.handle] takes its "no eligible
 * device" branch — enough to prove the action reaches the real, shared ViewModel without needing a
 * real/fake device.
 */
// BasePlatformTestCase's light project — and therefore the cached project.service<AdbToolboxProjectService>()
// singleton and its one shared feedbackViewModel — is reused not just across this class's own test
// methods but across every BasePlatformTestCase in this Gradle test JVM (e.g.
// dev.acme.adbtoolbox.intellij.mirroring.MirroringToggleActionTest also drives the same singleton's
// feedback channel). Since that channel's toast queue is bounded (FeedbackViewModel's maxStack),
// each test here dismisses the exact toast(s) it just posted immediately after asserting on them,
// so it never leaves state behind that could push another test class's queue past its bound.
class RestartAppActionTest : BasePlatformTestCase() {

    fun `test invoking the action forwards Restart to the shared app lifecycle view model`() {
        val composition = project.service<AdbToolboxProjectService>()
        val action = RestartAppAction()
        val event = TestActionEvent.createTestEvent(action) { key ->
            if (key === CommonDataKeys.PROJECT.name) project else null
        }

        action.actionPerformed(event)

        val toast = composition.feedbackViewModel.state.value.toasts.last()
        assertEquals(FeedbackSeverity.Warning, toast.severity)
        assertEquals("No eligible device selected", toast.text)
        composition.feedbackViewModel.handle(FeedbackIntent.Dismiss(toast.id))
    }

    fun `test update enables the action only when a project is available`() {
        val action = RestartAppAction()
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
        val action = RestartAppAction()

        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun `test the action and a direct view model call both reach the same shared instance`() {
        val composition = project.service<AdbToolboxProjectService>()
        val before = composition.feedbackViewModel.state.value.toasts.map { it.id }.toSet()
        val action = RestartAppAction()
        val event = TestActionEvent.createTestEvent(action) { key ->
            if (key === CommonDataKeys.PROJECT.name) project else null
        }

        action.actionPerformed(event)
        composition.appLifecycleViewModel.handle(AppLifecycleIntent.Restart)

        // Both calls landed on the exact same shared instance/feedback channel — two new
        // "no eligible device" warnings appended to it, never two independently-tracked lifecycle
        // view models. Compared by id set (not raw size) so this assertion is immune to the bounded
        // toast queue evicting older, unrelated entries from other test classes sharing this
        // project's singleton.
        val added = composition.feedbackViewModel.state.value.toasts.filterNot { it.id in before }
        assertEquals(2, added.size)
        added.forEach {
            assertEquals(FeedbackSeverity.Warning, it.severity)
            assertEquals("No eligible device selected", it.text)
            composition.feedbackViewModel.handle(FeedbackIntent.Dismiss(it.id))
        }
    }
}
