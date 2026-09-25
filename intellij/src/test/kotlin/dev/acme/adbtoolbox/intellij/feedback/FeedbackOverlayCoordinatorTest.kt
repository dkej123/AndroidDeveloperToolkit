package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive

/**
 * Connects task 013's [FeedbackViewModel] to task 010's [AdbToolboxHostPanel] via
 * [FeedbackOverlayCoordinator]. Kept as a [BasePlatformTestCase] like every other test in this
 * module. Exercises [FeedbackOverlayCoordinator.render] directly against constructed
 * [FeedbackViewState] values — the same "invoke the handler directly, don't depend on a real
 * coroutine round trip" pattern
 * [dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinatorTest] documents for this headless
 * sandbox. A separate smoke test constructs a real [FeedbackViewModel] to exercise wiring/disposal
 * without asserting on async state propagation.
 */
class FeedbackOverlayCoordinatorTest : BasePlatformTestCase() {

    private fun labelsOf(container: java.awt.Container): List<javax.swing.JLabel> =
        container.components.flatMap { child ->
            val nested = if (child is java.awt.Container) labelsOf(child) else emptyList()
            (if (child is javax.swing.JLabel) listOf(child) else emptyList()) + nested
        }

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: collector renders are queued on the EDT behind the test body, so a
        // direct render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private fun coordinator(host: AdbToolboxHostPanel): FeedbackOverlayCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = FeedbackViewModel(scope, dispatchers)
        return FeedbackOverlayCoordinator(host, viewModel, scope, dispatchers)
    }

    fun `test construction mounts the status panel into the feedback slot`() {
        val host = AdbToolboxHostPanel()

        val coordinator = coordinator(host)

        assertTrue(host.feedbackSlot.components.contains(coordinator.statusPanel))
        coordinator.dispose()
    }

    fun `test construction shows exactly one toast overlay`() {
        val host = AdbToolboxHostPanel()
        val before = host.overlays.overlayCount

        coordinator(host)

        assertEquals(before + 1, host.overlays.overlayCount)
    }

    fun `test rendering a state with toasts updates the status panel`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)

        coordinator.render(
            FeedbackViewState(
                toasts = listOf(FeedbackMessage("a", "Refreshed", FeedbackSeverity.Success)),
                status = StatusState(message = "Refreshed"),
            ),
        )

        val labels = labelsOf(coordinator.statusPanel)
        assertTrue(labels.any { it.text == "Refreshed" })

        coordinator.dispose()
    }

    fun `test rendering an in-progress process indicator updates the status panel`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)

        coordinator.render(FeedbackViewState(status = StatusState(process = ProcessIndicator.InProgress("scrcpy"))))

        val labels = labelsOf(coordinator.statusPanel)
        assertTrue(labels.any { it.text == "scrcpy" })

        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope and dismisses its toast overlay`() {
        val host = AdbToolboxHostPanel()
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = FeedbackViewModel(scope, dispatchers)
        val before = host.overlays.overlayCount
        val coordinator = FeedbackOverlayCoordinator(host, viewModel, scope, dispatchers)
        assertEquals(before + 1, host.overlays.overlayCount)

        coordinator.dispose()

        assertFalse(scope.isActive)
        assertEquals(before, host.overlays.overlayCount)
    }

    fun `test disposing the host also removes the coordinator's toast overlay`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        val toastOverlay = host.feedbackSlot.getComponent(0)

        host.dispose()

        assertEquals(0, host.overlays.overlayCount)
        coordinator.dispose()
        assertNotNull(toastOverlay)
    }

    fun `test the coordinator wires up against a real view model without a construction-time crash`() {
        val host = AdbToolboxHostPanel()
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = FeedbackViewModel(scope, dispatchers)

        val coordinator = FeedbackOverlayCoordinator(host, viewModel, scope, dispatchers)

        assertNotNull(coordinator.statusPanel)
        coordinator.dispose()
    }
}
