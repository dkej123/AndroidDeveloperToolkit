package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.dispatch.IdeDispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive

/**
 * Task 010 wires the neutral [AdbToolboxHostPanel] (named slots for device context, navigation,
 * active view, and feedback) into the ToolWindow content this panel mounts, replacing the bare
 * placeholder from task 007. Task 013 replaces the feedback slot's `ShellViewModel` status-text
 * stand-in with the real [FeedbackViewModel]-driven feedback/status/toast infrastructure. Kept as
 * a [BasePlatformTestCase] like every other test in this module.
 */
class AdbToolboxToolWindowPanelTest : BasePlatformTestCase() {

    // IdeDispatcherProvider() touches ApplicationManager.getApplication() at construction, which
    // is not yet initialized during JUnit-3-style field initialization (before setUp() runs) — so
    // it is constructed fresh per test method instead of as a class-level val, matching
    // AdbToolboxProjectServiceTest's pattern of only touching platform services inside test bodies.
    private fun dispatchers(): DispatcherProvider = IdeDispatcherProvider()

    private fun navigationViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider): NavigationViewModel =
        NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device)

    private class Harness(dispatchers: DispatcherProvider) {
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val navigationScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val feedbackScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    }

    private fun panel(dispatchers: DispatcherProvider, harness: Harness): AdbToolboxToolWindowPanel =
        AdbToolboxToolWindowPanel(
            viewModel = ShellViewModel(harness.scope, dispatchers),
            dispatchers = dispatchers,
            scope = harness.scope,
            navigationViewModel = navigationViewModel(harness.navigationScope, dispatchers),
            navigationScope = harness.navigationScope,
            feedbackViewModel = FeedbackViewModel(harness.feedbackScope, dispatchers),
            feedbackScope = harness.feedbackScope,
        )

    fun `test the panel mounts a host with the named slots`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertNotNull(panel.host.deviceContextSlot)
        assertNotNull(panel.host.navigationSlot)
        assertNotNull(panel.host.activeViewHost)
        assertNotNull(panel.host.feedbackSlot)

        panel.dispose()
    }

    fun `test the navigation rail is mounted into the navigation slot`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.navigationSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test the feedback status panel is mounted into the feedback slot`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.feedbackSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test disposing the panel disposes its host and cancels the feedback scope too`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)
        val overlay = javax.swing.JLabel("toast")
        panel.host.overlays.show(overlay)

        panel.dispose()

        assertFalse(panel.host.overlays.isShowing(overlay))
        assertFalse(harness.scope.isActive)
        assertFalse(harness.navigationScope.isActive)
        assertFalse(harness.feedbackScope.isActive)
    }
}
