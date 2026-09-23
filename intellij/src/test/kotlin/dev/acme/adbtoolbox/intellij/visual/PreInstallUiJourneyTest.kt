package dev.acme.adbtoolbox.intellij.visual

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsRow
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.intellij.apps.AppsPanel
import dev.acme.adbtoolbox.intellij.display.DisplayPanel
import dev.acme.adbtoolbox.intellij.logcat.LogcatPanel
import dev.acme.adbtoolbox.intellij.logcat.LogcatVirtualList
import dev.acme.adbtoolbox.intellij.network.NetworkPanel

/** High-risk, pre-install user journeys driven through real Swing controls. */
class PreInstallUiJourneyTest : BasePlatformTestCase() {

    fun `test apps search selection and restart journey`() {
        var query = ""
        var restarted = 0
        val panel = AppsPanel(
            onQueryChange = { query = it },
            onToggleSystemPackages = {},
            onSelect = {},
            onClearFilter = {},
            onRestart = { restarted++ },
        )

        panel.searchFieldForTest.text = "shop"
        assertEquals("shop", query)
        panel.update(
            AppsViewState(
                query = "shop",
                hasDevice = true,
                isLoading = false,
                rows = listOf(AppsRow("com.acme.shop", "Acme Shop", true, true, true)),
                selectedPackageName = "com.acme.shop",
            ),
        )
        panel.updateLifecycle(AppLifecycleViewState(ControlPolicy.Enabled, "com.acme.shop", busy = false))
        panel.restartButtonForTest.doClick()

        assertEquals("Acme Shop", panel.selectedAppLabelForTest)
        assertEquals(1, restarted)
    }

    fun `test display rejects an invalid custom value then applies a valid value and toggle`() {
        val applied = mutableListOf<Double>()
        var darkTheme: Boolean? = null
        val panel = DisplayPanel(
            onApplyFontScale = applied::add,
            onResetFontScale = {},
            onApplyDensityPreset = {},
            onApplyCustomDensity = {},
            onResetDensity = {},
            onSetDarkTheme = { darkTheme = it },
            onSetShowTouches = {},
            onSetAnimationsOff = {},
        )

        panel.fontChipRowForTest.chips.first { it.text == "Custom…" }.doClick()
        panel.fontCustomFieldForTest.text = "abc"
        panel.fontCustomFieldForTest.postActionEvent()
        assertTrue(panel.fontCustomErrorLabelForTest.isVisible)
        assertTrue(applied.isEmpty())

        panel.fontCustomFieldForTest.text = "1.3"
        panel.fontCustomFieldForTest.postActionEvent()
        panel.darkThemeToggleForTest.doClick()
        assertEquals(listOf(1.3), applied)
        assertEquals(true, darkTheme)
    }

    fun `test proxy validation protects enable then active Disable resets device state`() {
        var enabled = 0
        var reset = 0
        val panel = NetworkPanel({}, {}, {}, { enabled++ }, { reset++ }, {})
        panel.update(
            ProxyViewState(
                isDeviceEligible = true,
                hostInput = "10.0.4.117",
                portInput = "99999",
                portError = "Port must be 1-65535",
            ),
        )
        panel.enableButtonForTest.doClick()
        assertEquals(0, enabled)

        val endpoint = endpoint("10.0.4.117", 8888)
        panel.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Active(endpoint)))
        panel.enableButtonForTest.doClick()
        assertEquals(1, reset)
    }

    fun `test logcat search filters pause wrap clear and resume controls dispatch`() {
        val events = mutableListOf<String>()
        var level: LogSeverity? = null
        val panel = LogcatPanel(
            LogcatVirtualList(),
            onQueryChange = { events += "query:$it" },
            onSetMinSeverity = { level = it },
            onTogglePackageFilter = { events += "package" },
            onTogglePause = { events += "pause" },
            onToggleFollow = { events += "follow" },
            onToggleWrap = { events += "wrap" },
            onClearLocal = { events += "clear" },
            onJumpToLatest = { events += "latest" },
            onResetFilters = { events += "reset" },
            onManualScrollAway = {},
        )

        panel.searchFieldForTest.text = "timeout"
        panel.levelButtonsForTest.getValue(LogSeverity.ERROR).doClick()
        panel.pauseButtonForTest.doClick()
        panel.wrapButtonForTest.doClick()
        panel.clearButtonForTest.doClick()
        panel.jumpToLatestLinkForTest.doClick()

        assertEquals(LogSeverity.ERROR, level)
        assertContainsElements(events, "query:timeout", "pause", "wrap", "clear", "latest")
    }

    private fun endpoint(host: String, port: Int) = ProxyEndpoint(
        (ProxyHost.parse(host) as ProxyHostResult.Valid).host,
        (ProxyPort.parse(port) as ProxyPortResult.Valid).port,
    )
}
