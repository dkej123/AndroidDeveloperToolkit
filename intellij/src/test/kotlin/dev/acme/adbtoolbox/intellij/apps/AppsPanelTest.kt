package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsRow
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.apps.ClearDataViewState
import dev.acme.adbtoolbox.application.apps.UninstallViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy

/**
 * [AppsPanel] is the toolbar (search + "show system packages" toggle) plus [AppsVirtualList], the
 * two-line empty state, and the pinned action footer with its selected-app row and "Destructive"
 * group (task 045, `design/README.md` §4).
 */
class AppsPanelTest : BasePlatformTestCase() {

    private fun row(name: String, selected: Boolean = false) =
        AppsRow(packageName = name, label = name, labelResolved = false, isDebuggable = null, isSelected = selected)

    fun `test typing in the search field invokes onQueryChange with the exact text`() {
        var query: String? = null
        val panel = AppsPanel(onQueryChange = { query = it }, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.searchFieldForTest.text = "shop"
        panel.searchFieldForTest.document.let { doc ->
            // Force the document listener path a real keystroke would take.
            doc.insertString(doc.length, "!", null)
            doc.remove(doc.length - 1, 1)
        }

        assertEquals("shop", query)
    }

    fun `test clicking the system-packages toggle invokes onToggleSystemPackages`() {
        var toggled = 0
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = { toggled++ }, onSelect = {}, onClearFilter = {})

        panel.systemToggleForTest.doClick()

        assertEquals(1, toggled)
    }

    fun `test update renders rows and reflects the search query and toggle state`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(
            AppsViewState(
                query = "sh",
                showSystemPackages = true,
                hasDevice = true,
                isLoading = false,
                rows = listOf(row("com.acme.shop")),
            ),
        )

        assertEquals("sh", panel.searchFieldForTest.text)
        assertTrue(panel.systemToggleForTest.isSelected)
        assertEquals(1, panel.listForTest.model.size)
    }

    fun `test a filtered-empty state shows the empty message and a working clear-filter action`() {
        var cleared = false
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = { cleared = true })

        panel.update(AppsViewState(query = "nomatch", hasDevice = true, isLoading = false, rows = emptyList()))

        assertTrue(panel.emptyStateTextForTest.contains("No packages match “nomatch”"))
        assertTrue(panel.emptyStateTextForTest.contains("Search matches package name and app label."))
        panel.clearFilterLinkForTest.doClick()
        assertTrue(cleared)
    }

    fun `test a genuinely empty device shows its own message without a clear-filter link`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(AppsViewState(query = "", hasDevice = true, isLoading = false, rows = emptyList()))

        assertTrue(panel.emptyStateTextForTest.contains("No packages found"))
        assertFalse(panel.clearFilterLinkForTest.isVisible)
    }

    fun `test the toolbar clear-query button only appears once a query is typed`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})
        assertFalse(panel.clearQueryButtonForTest.isVisible)

        panel.update(AppsViewState(query = "sh", hasDevice = true, isLoading = false, rows = emptyList()))
        assertTrue(panel.clearQueryButtonForTest.isVisible)

        panel.update(AppsViewState(query = "", hasDevice = true, isLoading = false, rows = emptyList()))
        assertFalse(panel.clearQueryButtonForTest.isVisible)
    }

    fun `test clicking the toolbar clear-query button invokes onClearFilter`() {
        var cleared = false
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = { cleared = true })
        panel.update(AppsViewState(query = "sh", hasDevice = true, isLoading = false, rows = emptyList()))

        panel.clearQueryButtonForTest.doClick()

        assertTrue(cleared)
    }

    fun `test the selected app's label and package surface in the pinned footer`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(
            AppsViewState(
                hasDevice = true,
                isLoading = false,
                rows = listOf(
                    AppsRow("com.acme.shop", "Acme Shop", labelResolved = true, isDebuggable = null, isSelected = true),
                ),
                selectedPackageName = "com.acme.shop",
            ),
        )

        assertEquals("Acme Shop", panel.selectedAppLabelForTest)
        assertEquals("com.acme.shop", panel.selectedAppPackageForTest)
    }

    fun `test the footer clears the selected-app row when nothing is selected`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(AppsViewState(hasDevice = true, isLoading = false, rows = emptyList(), selectedPackageName = null))

        assertEquals("", panel.selectedAppLabelForTest)
        assertEquals("", panel.selectedAppPackageForTest)
    }

    fun `test the destructive group is labeled and starts with Clear data and Uninstall disabled`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        assertEquals("DESTRUCTIVE", panel.destructiveLabelForTest)
        assertFalse(panel.clearDataButtonForTest.isEnabled)
        assertFalse(panel.uninstallButtonForTest.isEnabled)
    }

    fun `test with no device the panel shows no rows and no crash`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(AppsViewState(hasDevice = false, isLoading = false, rows = emptyList()))

        assertEquals(0, panel.listForTest.model.size)
    }

    fun `test disposing the panel tears down its list without throwing`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})
        panel.update(AppsViewState(hasDevice = true, isLoading = false, rows = listOf(row("com.acme.shop"))))

        panel.disposePanel()
    }

    fun `test the lifecycle action buttons start disabled`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        assertFalse(panel.restartButtonForTest.isEnabled)
        assertFalse(panel.forceStopButtonForTest.isEnabled)
        assertFalse(panel.launchButtonForTest.isEnabled)
    }

    fun `test updateLifecycle enables the action buttons only when actions are enabled`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false))

        assertTrue(panel.restartButtonForTest.isEnabled)
        assertTrue(panel.forceStopButtonForTest.isEnabled)
        assertTrue(panel.launchButtonForTest.isEnabled)
    }

    fun `test updateLifecycle disables the action buttons while an action is busy`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})
        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false))

        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = true))

        assertFalse(panel.restartButtonForTest.isEnabled)
        assertFalse(panel.forceStopButtonForTest.isEnabled)
        assertFalse(panel.launchButtonForTest.isEnabled)
    }

    // ---- Task 050: disabled action buttons must name the actual reason, and clear it once enabled ----

    fun `test updateLifecycle names no-device as the disabled reason`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.updateLifecycle(
            AppLifecycleViewState(
                controlPolicy = ControlPolicy.Disabled(dev.acme.adbtoolbox.domain.device.DeviceCommandContext.Disabled.NoDeviceSelected),
                selectedPackageName = null,
                busy = false,
            ),
        )

        assertTrue(panel.restartButtonForTest.toolTipText.endsWith("Connect a device to use this"))
        assertTrue(panel.forceStopButtonForTest.toolTipText.endsWith("Connect a device to use this"))
        assertTrue(panel.launchButtonForTest.toolTipText.endsWith("Connect a device to use this"))
    }

    fun `test updateLifecycle names no-selected-package as the disabled reason when a device is online`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = null, busy = false))

        assertTrue(panel.restartButtonForTest.toolTipText.endsWith("Select an app to use this"))
    }

    fun `test updateLifecycle names busy as the disabled reason, and clears the reason once actions are enabled`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})
        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = true))

        assertTrue(panel.restartButtonForTest.toolTipText.endsWith("An action is already running for this app"))

        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false))

        assertTrue(panel.restartButtonForTest.toolTipText.startsWith("Force-stop, then launch the main activity"))
        if (!com.intellij.openapi.util.SystemInfo.isMac) assertFalse('⌘' in panel.restartButtonForTest.toolTipText)
        assertEquals("am force-stop — leaves data intact", panel.forceStopButtonForTest.toolTipText)
        assertEquals("monkey launch of the main activity", panel.launchButtonForTest.toolTipText)
    }

    fun `test clicking restart, force-stop, and launch invoke their own callbacks`() {
        var restarted = 0
        var forceStopped = 0
        var launched = 0
        val panel = AppsPanel(
            onQueryChange = {},
            onToggleSystemPackages = {},
            onSelect = {},
            onClearFilter = {},
            onRestart = { restarted++ },
            onForceStop = { forceStopped++ },
            onLaunch = { launched++ },
        )
        panel.updateLifecycle(AppLifecycleViewState(controlPolicy = ControlPolicy.Enabled, selectedPackageName = "com.acme.shop", busy = false))

        panel.restartButtonForTest.doClick()
        panel.forceStopButtonForTest.doClick()
        panel.launchButtonForTest.doClick()

        assertEquals(1, restarted)
        assertEquals(1, forceStopped)
        assertEquals(1, launched)
    }

    fun `test Clear data starts disabled and follows its own view state`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        assertFalse(panel.clearDataButtonForTest.isEnabled)

        panel.updateClearData(
            ClearDataViewState(
                controlPolicy = ControlPolicy.Enabled,
                selectedPackageName = "com.acme.shop",
                busy = false,
            ),
        )
        assertTrue(panel.clearDataButtonForTest.isEnabled)

        panel.updateClearData(
            ClearDataViewState(
                controlPolicy = ControlPolicy.Enabled,
                selectedPackageName = "com.acme.shop",
                busy = true,
            ),
        )
        assertFalse(panel.clearDataButtonForTest.isEnabled)
        assertTrue(panel.clearDataButtonForTest.toolTipText.endsWith("An action is already running for this app"))
    }

    fun `test clicking Clear data invokes only its callback`() {
        var clearDataRequests = 0
        val panel = AppsPanel(
            onQueryChange = {},
            onToggleSystemPackages = {},
            onSelect = {},
            onClearFilter = {},
            onClearData = { clearDataRequests++ },
        )
        panel.updateClearData(
            ClearDataViewState(
                controlPolicy = ControlPolicy.Enabled,
                selectedPackageName = "com.acme.shop",
                busy = false,
            ),
        )

        panel.clearDataButtonForTest.doClick()

        assertEquals(1, clearDataRequests)
    }

    fun `test Uninstall starts disabled and follows its own view state`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        assertFalse(panel.uninstallButtonForTest.isEnabled)

        panel.updateUninstall(
            UninstallViewState(
                controlPolicy = ControlPolicy.Enabled,
                selectedPackageName = "com.acme.shop",
                busy = false,
            ),
        )
        assertTrue(panel.uninstallButtonForTest.isEnabled)

        panel.updateUninstall(
            UninstallViewState(
                controlPolicy = ControlPolicy.Enabled,
                selectedPackageName = "com.acme.shop",
                busy = true,
            ),
        )
        assertFalse(panel.uninstallButtonForTest.isEnabled)
        assertTrue(panel.uninstallButtonForTest.toolTipText.endsWith("An action is already running for this app"))
    }

    fun `test clicking Uninstall invokes only its callback`() {
        var uninstallRequests = 0
        val panel = AppsPanel(
            onQueryChange = {},
            onToggleSystemPackages = {},
            onSelect = {},
            onClearFilter = {},
            onUninstall = { uninstallRequests++ },
        )
        panel.updateUninstall(
            UninstallViewState(
                controlPolicy = ControlPolicy.Enabled,
                selectedPackageName = "com.acme.shop",
                busy = false,
            ),
        )

        panel.uninstallButtonForTest.doClick()

        assertEquals(1, uninstallRequests)
    }
}
