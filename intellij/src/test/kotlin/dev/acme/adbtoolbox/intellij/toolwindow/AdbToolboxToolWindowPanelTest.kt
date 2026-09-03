package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
 * placeholder from task 007. Kept as a [BasePlatformTestCase] like every other test in this module.
 */
class AdbToolboxToolWindowPanelTest : BasePlatformTestCase() {

    // IdeDispatcherProvider() touches ApplicationManager.getApplication() at construction, which
    // is not yet initialized during JUnit-3-style field initialization (before setUp() runs) — so
    // it is constructed fresh per test method instead of as a class-level val, matching
    // AdbToolboxProjectServiceTest's pattern of only touching platform services inside test bodies.
    private fun dispatchers(): DispatcherProvider = IdeDispatcherProvider()

    private fun navigationViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider): NavigationViewModel =
        NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device)

    fun `test the panel mounts a host with the named slots`() {
        val dispatchers = dispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val navigationScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val panel = AdbToolboxToolWindowPanel(
            ShellViewModel(scope, dispatchers),
            dispatchers,
            scope,
            navigationViewModel(navigationScope, dispatchers),
            navigationScope,
        )

        assertNotNull(panel.host.deviceContextSlot)
        assertNotNull(panel.host.navigationSlot)
        assertNotNull(panel.host.activeViewHost)
        assertNotNull(panel.host.feedbackSlot)

        panel.dispose()
    }

    fun `test the navigation rail is mounted into the navigation slot`() {
        val dispatchers = dispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val navigationScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val panel = AdbToolboxToolWindowPanel(
            ShellViewModel(scope, dispatchers),
            dispatchers,
            scope,
            navigationViewModel(navigationScope, dispatchers),
            navigationScope,
        )

        assertTrue(panel.host.navigationSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test disposing the panel disposes its host too`() {
        val dispatchers = dispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val navigationScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val panel = AdbToolboxToolWindowPanel(
            ShellViewModel(scope, dispatchers),
            dispatchers,
            scope,
            navigationViewModel(navigationScope, dispatchers),
            navigationScope,
        )
        val overlay = javax.swing.JLabel("toast")
        panel.host.overlays.show(overlay)

        panel.dispose()

        assertFalse(panel.host.overlays.isShowing(overlay))
        assertFalse(scope.isActive)
        assertFalse(navigationScope.isActive)
    }
}
