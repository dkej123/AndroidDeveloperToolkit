package io.github.dkej123.devicecockpit.intellij.currentapp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppAction
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppDisplay
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppSnapshot
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppViewState
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.github.dkej123.devicecockpit.domain.foreground.PackageDetails
import io.github.dkej123.devicecockpit.domain.foreground.ProcessInfo
import io.github.dkej123.devicecockpit.domain.foreground.RuntimePermission
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

class CurrentAppSectionTest : BasePlatformTestCase() {
    private val actions = mutableListOf<Pair<CurrentAppAction, String>>()
    private val details = mutableListOf<String>()
    private val resets = mutableListOf<Pair<String, String>>()
    private var refreshes = 0

    private fun section() = CurrentAppSection(
        identity = { pkg -> if (pkg == "com.acme.shop") AppIdentity("Acme Shop", null) else null },
        onAction = { action, pkg -> actions += action to pkg },
        onDetails = { details += it },
        onRefresh = { refreshes++ },
        onResetPermission = { pkg, permission -> resets += pkg to permission },
        onApplyPending = {},
        onWake = {},
        onLaunchLast = {},
    )

    private fun app(
        killed: Boolean = false,
        system: Boolean = false,
        debuggable: Boolean = true,
        systemUi: Boolean = false,
        permissions: List<RuntimePermission> = emptyList(),
    ) = CurrentAppDisplay.App(
        snapshot = CurrentAppSnapshot(
            foreground = ForegroundState.App("com.acme.shop", ".checkout.CheckoutActivity"),
            details = PackageDetails("4.12.0-dev", 41200, 26, 36, debuggable = debuggable, system = system, runtimePermissions = permissions),
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
        assertEquals("", s.metaTextForTest)
        s.refreshButtonForTest.doClick()
        assertEquals(1, refreshes)
        assertEquals("Restart", s.primaryButtonForTest.text)
        assertTrue(s.killButtonForTest.isEnabled)
        assertTrue(s.uninstallButtonForTest.isEnabled)

        s.primaryButtonForTest.doClick()
        s.killButtonForTest.doClick()
        s.detailsLinkForTest.doClick()
        assertEquals(listOf(CurrentAppAction.Restart to "com.acme.shop", CurrentAppAction.Kill to "com.acme.shop"), actions)
        assertEquals(listOf("com.acme.shop"), details)
    }

    fun `test an app the Apps list does not know yet shows the name read from the device`() {
        val s = section()
        val fresh = app().let {
            it.copy(
                app = ForegroundState.App("pl.acme.hr.debug", ".MainActivity"),
                snapshot = it.snapshot.copy(
                    foreground = ForegroundState.App("pl.acme.hr.debug", ".MainActivity"),
                    info = io.github.dkej123.devicecockpit.domain.packages.AppInfo("pl.acme.hr.debug", "HR Pill", isDebuggable = true, icon = null),
                ),
            )
        }

        s.update(CurrentAppViewState(display = fresh, deviceOnline = true))

        assertEquals("HR Pill", s.labelForTest)
    }

    fun `test process death is offered for a running app only`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(), deviceOnline = true))

        assertTrue(s.processDeathButtonForTest.isEnabled)
        s.processDeathButtonForTest.doClick()
        assertEquals(listOf(CurrentAppAction.ProcessDeath to "com.acme.shop"), actions)

        s.update(CurrentAppViewState(display = app(killed = true), deviceOnline = true))
        assertFalse(s.processDeathButtonForTest.isEnabled)
    }

    fun `test every action stays whole and inside a narrow tool window`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(), updatedAtMillis = 8_000, deviceOnline = true))

        s.setSize(380, 2_000)
        layoutTree(s)
        s.setSize(380, s.preferredSize.height)
        layoutTree(s)

        val actions = listOf(s.primaryButtonForTest, s.killButtonForTest, s.processDeathButtonForTest, s.resetPermissionsButtonForTest, s.resetMoreButtonForTest, s.detailsLinkForTest)
        val bounds = actions.associate { it.text.ifEmpty { "caret" } to SwingUtilities.convertRectangle(it.parent, it.bounds, s) }
        bounds.forEach { (name, rect) ->
            assertTrue("$name is cut: $rect", rect.width >= actions.first { (it.text.ifEmpty { "caret" }) == name }.preferredSize.width)
            assertTrue("$name leaves the section: $rect", rect.x >= 0 && rect.x + rect.width <= s.width && rect.y + rect.height <= s.height)
        }
        bounds.values.toList().let { rects ->
            rects.forEachIndexed { i, a -> rects.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.intersects(b)) } }
        }
    }

    private fun layoutTree(component: java.awt.Component) {
        if (component is java.awt.Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    fun `test the process time keeps counting between polls`() {
        val clock = kotlin.time.TestTimeSource()
        val s = section()
        val running = app().let { it.copy(snapshot = it.snapshot.copy(process = ProcessInfo(8155, 192.seconds, clock.markNow()))) }
        s.update(CurrentAppViewState(display = running, deviceOnline = true))

        clock += 3.seconds
        s.tickForTest()

        assertEquals("8155 · 3m 15s", s.processForTest)
    }

    fun `test a killed app reads not running with Launch`() {
        val s = section()
        s.update(CurrentAppViewState(display = app(killed = true), deviceOnline = true))

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
        assertEquals("Retry", s.noteLinkForTest.text)
    }

    fun `test the reset menu offers all permissions or one granted permission`() {
        val s = section()
        s.update(
            CurrentAppViewState(
                display = app(
                    permissions = listOf(
                        RuntimePermission("android.permission.CAMERA", granted = true, flags = emptySet()),
                        RuntimePermission("android.permission.ACCESS_FINE_LOCATION", granted = true, flags = setOf("POLICY_FIXED")),
                        RuntimePermission("android.permission.RECORD_AUDIO", granted = false, flags = emptySet()),
                    ),
                ),
                deviceOnline = true,
            ),
        )

        val items = s.resetChoicesForTest()
        assertEquals(listOf("All granted permissions (2)", "Camera", "Access fine location"), items.map { it.label })
        assertEquals(listOf(false, true, false), items.map { it.separatorAbove })
        assertEquals("android.permission.CAMERA", items[1].description)
        assertFalse(items[2].enabled)
        items[1].run()
        items[0].run()
        assertEquals(listOf("com.acme.shop" to "android.permission.CAMERA"), resets)
        assertEquals(listOf(CurrentAppAction.ResetPermissions to "com.acme.shop"), actions)
    }
}
