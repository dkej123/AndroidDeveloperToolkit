package io.github.dkej123.devicecockpit.intellij.currentapp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppAction
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppDisplay
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppSnapshot
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppViewState
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.github.dkej123.devicecockpit.domain.foreground.PackageDetails
import io.github.dkej123.devicecockpit.domain.foreground.ProcessInfo
import kotlin.time.Duration.Companion.seconds

class CurrentAppSectionTest : BasePlatformTestCase() {
    private val actions = mutableListOf<Pair<CurrentAppAction, String>>()
    private val details = mutableListOf<String>()
    private var nowMillis = 10_000L

    private fun section() = CurrentAppSection(
        identity = { pkg -> if (pkg == "com.acme.shop") AppIdentity("Acme Shop", null) else null },
        onAction = { action, pkg -> actions += action to pkg },
        onDetails = { details += it },
        onRefresh = {},
        onApplyPending = {},
        onWake = {},
        onLaunchLast = {},
        now = { nowMillis },
    )

    private fun app(
        killed: Boolean = false,
        system: Boolean = false,
        debuggable: Boolean = true,
        systemUi: Boolean = false,
    ) = CurrentAppDisplay.App(
        snapshot = CurrentAppSnapshot(
            foreground = ForegroundState.App("com.acme.shop", ".checkout.CheckoutActivity"),
            details = PackageDetails("4.12.0-dev", 41200, 26, 36, debuggable = debuggable, system = system, runtimePermissions = emptyList()),
            process = if (killed) null else ProcessInfo(8155, 192.seconds),
        ),
        app = ForegroundState.App("com.acme.shop", ".checkout.CheckoutActivity"),
        killed = killed,
        systemUi = systemUi,
    )

    fun `test a running debuggable app shows its facts and every action`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(), updatedAtMillis = 8_000, deviceOnline = true))

        assertEquals("Acme Shop", s.labelForTest)
        assertEquals("debug", s.tagForTest)
        assertEquals(".checkout.CheckoutActivity", s.activityForTest)
        assertEquals("8155 · 3m 12s", s.processForTest)
        assertEquals("updated 2 s ago", s.metaTextForTest)
        assertEquals("Restart", s.primaryButtonForTest.text)
        assertTrue(s.killButtonForTest.isEnabled)
        assertTrue(s.uninstallButtonForTest.isEnabled)

        s.primaryButtonForTest.doClick()
        s.killButtonForTest.doClick()
        s.detailsLinkForTest.doClick()
        assertEquals(listOf(CurrentAppAction.Restart to "com.acme.shop", CurrentAppAction.Kill to "com.acme.shop"), actions)
        assertEquals(listOf("com.acme.shop"), details)
    }

    fun `test a killed app reads not running with Launch`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(killed = true), deviceOnline = true))

        assertEquals("not running", s.metaTextForTest)
        assertEquals("—", s.activityForTest)
        assertEquals("not running", s.processForTest)
        assertEquals("Launch", s.primaryButtonForTest.text)
        assertFalse(s.killButtonForTest.isEnabled)
        s.primaryButtonForTest.doClick()
        assertEquals(CurrentAppAction.Launch to "com.acme.shop", actions.single())
    }

    fun `test a system app cannot be uninstalled and System UI has no actions`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(system = true, debuggable = false), deviceOnline = true))
        assertEquals("system", s.tagForTest)
        assertFalse(s.uninstallButtonForTest.isEnabled)
        assertEquals("Preinstalled system app — adb can’t uninstall it", s.uninstallButtonForTest.toolTipText)
        assertTrue(s.clearDataButtonForTest.isEnabled)

        s.update(CurrentAppViewState(display = app(systemUi = true), deviceOnline = true))
        assertEquals("System UI", s.labelForTest)
        assertFalse(s.primaryButtonForTest.isEnabled)
        assertFalse(s.clearDataButtonForTest.isEnabled)
        assertTrue(s.detailsLinkForTest.isEnabled)
    }

    fun `test home and lock screens collapse to a note`() {
        val s = section()
        s.update(CurrentAppViewState(display = CurrentAppDisplay.Home("com.launcher", "com.acme.shop"), deviceOnline = true))
        assertEquals("Home screen", s.labelForTest)
        assertEquals("Last app: Acme Shop", s.noteForTest)
        assertEquals("Launch", s.noteLinkForTest.text)

        s.update(CurrentAppViewState(display = CurrentAppDisplay.Home("com.launcher", null), deviceOnline = true))
        assertEquals("No app in the foreground.", s.noteForTest)
        assertFalse(s.noteLinkForTest.isVisible)

        s.update(CurrentAppViewState(display = CurrentAppDisplay.Locked, deviceOnline = true))
        assertEquals("Lock screen", s.labelForTest)
        assertEquals("Wake", s.noteLinkForTest.text)
    }

    fun `test a held change is announced in the header`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(), pendingPackage = "com.maps", deviceOnline = true))
        assertTrue(s.metaTextForTest.endsWith("came to the front · Update") || s.metaTextForTest.endsWith("in front · Update"))
    }

    fun `test an adb error offers Retry`() {
        val s = section()
        s.update(CurrentAppViewState(display = CurrentAppDisplay.Error("dumpsys activity activities — timed out"), deviceOnline = true))
        assertEquals("adb error", s.metaTextForTest)
        assertEquals("Retry", s.noteLinkForTest.text)
    }
}
