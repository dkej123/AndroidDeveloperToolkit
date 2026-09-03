package dev.acme.adbtoolbox.intellij.host

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel

/**
 * [FeatureViewHost] is the "active view" named slot (task 010). Kept as a [BasePlatformTestCase]
 * like every other test in this module — see `AdbTransportSelectionTest`'s class doc for why a
 * plain JUnit 5 test class here is not discovered by `:intellij:test`'s runner.
 */
class FeatureViewHostTest : BasePlatformTestCase() {

    fun `test a route key is not registered until registerFeatureView is called for it`() {
        val host = FeatureViewHost()

        assertFalse(host.isRegistered("device"))

        host.registerFeatureView("device") { JLabel("device") }

        assertTrue(host.isRegistered("device"))
    }

    fun `test registering a feature view mounts it as a child of the host`() {
        val host = FeatureViewHost()
        val view = JLabel("apps")

        host.registerFeatureView("apps") { view }

        assertSame(view, host.componentFor("apps"))
        assertTrue(view.parent === host)
    }

    fun `test switching between two registered views twice preserves each instance`() {
        val host = FeatureViewHost()
        val deviceView = JLabel("device")
        val appsView = JLabel("apps")
        host.registerFeatureView("device") { deviceView }
        host.registerFeatureView("apps") { appsView }

        host.show("apps")
        host.show("device")
        host.show("apps")
        host.show("device")

        assertSame(deviceView, host.componentFor("device"))
        assertSame(appsView, host.componentFor("apps"))
    }

    fun `test registering the same route key twice does not recreate the view`() {
        val host = FeatureViewHost()
        var constructionCount = 0

        val first = host.registerFeatureView("network") {
            constructionCount++
            JLabel("network")
        }
        val second = host.registerFeatureView("network") {
            constructionCount++
            JLabel("network (should not be built)")
        }

        assertSame(first, second)
        assertEquals(1, constructionCount)
    }

    fun `test showing an unregistered route key fails loudly instead of silently no-oping`() {
        val host = FeatureViewHost()

        try {
            host.show("logcat")
            fail("expected showing an unregistered route key to throw")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }
}
