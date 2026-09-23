package dev.acme.adbtoolbox.intellij.network

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme

/**
 * Task 047's final visual design for the Network view (`design/README.md` §6): header state meta
 * ("off"/"active"/"—"), invalid-port field styling, the active banner + Reset link, the
 * Enable/Disable primary action, "Use my computer IP", and the Recent list — mirroring
 * [dev.acme.adbtoolbox.intellij.display.DisplayPanelTest]'s structural/render coverage.
 */
class NetworkPanelTest : BasePlatformTestCase() {

    private fun endpoint(host: String, port: Int) = ProxyEndpoint(
        (ProxyHost.parse(host) as ProxyHostResult.Valid).host,
        (ProxyPort.parse(port) as ProxyPortResult.Valid).port,
    )

    private fun panel(
        onHostChange: (String) -> Unit = {},
        onPortChange: (String) -> Unit = {},
        onUseComputerIp: () -> Unit = {},
        onEnable: () -> Unit = {},
        onReset: () -> Unit = {},
        onSelectRecent: (ProxyEndpoint) -> Unit = {},
    ) = NetworkPanel(onHostChange, onPortChange, onUseComputerIp, onEnable, onReset, onSelectRecent)

    // ---- Off / ineligible-device state ----

    fun `test update with no device selected shows the em dash header state and disables the primary action`() {
        val p = panel()

        p.update(ProxyViewState(isDeviceEligible = false))

        assertEquals("—", p.proxyStateLabelForTest.text)
        assertFalse(p.enableButtonForTest.isEnabled)
        assertFalse(p.hostFieldForTest.isEnabled)
        assertFalse(p.activeBannerLabelForTest.isVisible)
    }

    fun `test the host and port fields expose accessible names for screen readers`() {
        val p = panel()

        assertEquals("Proxy host", p.hostFieldForTest.getAccessibleContext().accessibleName)
        assertEquals("Proxy port", p.portFieldForTest.getAccessibleContext().accessibleName)
    }

    fun `test an ineligible device names the blocker in the host field and Use my computer IP tooltips`() {
        val p = panel()

        p.update(ProxyViewState(isDeviceEligible = false))

        assertTrue(p.hostFieldForTest.toolTipText.endsWith("Connect a device to use this"))
        assertTrue(p.useComputerIpLinkForTest.toolTipText.endsWith("Connect a device to use this"))

        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Disabled))

        assertEquals("host or IP", p.hostFieldForTest.toolTipText)
        assertEquals("Fills your machine's LAN address", p.useComputerIpLinkForTest.toolTipText)
    }

    fun `test update with an eligible device and no active proxy shows off and Enable proxy`() {
        val p = panel()

        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Disabled))

        assertEquals("off", p.proxyStateLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.textFaint, p.proxyStateLabelForTest.foreground)
        assertEquals("Enable proxy", p.enableButtonForTest.text)
        assertFalse(p.activeBannerLabelForTest.isVisible)
    }

    fun `test blank host and port disables the primary action even when the device is eligible`() {
        val p = panel()

        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Disabled, hostInput = "", portInput = ""))

        assertFalse(p.enableButtonForTest.isEnabled)
    }

    // ---- Invalid port ----

    fun `test a port validation error renders the exact message and disables the primary action`() {
        val p = panel()

        p.update(
            ProxyViewState(
                isDeviceEligible = true,
                hostInput = "10.0.4.117",
                portInput = "99999",
                portError = "Port must be 1-65535",
            ),
        )

        assertEquals("Port must be 1-65535", p.errorLabelForTest.text)
        assertTrue(p.errorLabelForTest.isVisible)
        assertFalse(p.enableButtonForTest.isEnabled)
    }

    // ---- Active state ----

    fun `test update with an active readback shows active header state, the banner, and Disable`() {
        val p = panel()
        val active = endpoint("10.0.4.117", 8888)

        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Active(active)))

        assertEquals("active", p.proxyStateLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, p.proxyStateLabelForTest.foreground)
        assertTrue(p.activeBannerLabelForTest.isVisible)
        assertEquals("All traffic routed through 10.0.4.117:8888", p.activeBannerLabelForTest.text)
        assertEquals("Disable", p.enableButtonForTest.text)
        assertTrue(p.resetLinkForTest.isVisible)
        assertTrue(p.enableButtonForTest.isEnabled)
    }

    fun `test clicking Disable while active invokes onReset rather than onEnable`() {
        var resetCount = 0
        var enableCount = 0
        val p = panel(onReset = { resetCount++ }, onEnable = { enableCount++ })
        val active = endpoint("10.0.4.117", 8888)
        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Active(active)))

        p.enableButtonForTest.doClick()

        assertEquals(1, resetCount)
        assertEquals(0, enableCount)
    }

    fun `test clicking the banner Reset link invokes onReset`() {
        var resets = 0
        val p = panel(onReset = { resets++ })
        val active = endpoint("10.0.4.117", 8888)
        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Active(active)))

        p.resetLinkForTest.doClick()

        assertEquals(1, resets)
    }

    fun `test active banner text never overlaps Reset at dock width`() {
        val p = panel()
        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Active(endpoint("10.0.4.117", 8888))))
        p.setSize(346, 400)
        recursivelyLayout(p)

        val text = p.activeBannerLabelForTest
        val reset = p.resetLinkForTest
        val textRight = javax.swing.SwingUtilities.convertPoint(text.parent, text.x + text.width, text.y, p).x
        val resetLeft = javax.swing.SwingUtilities.convertPoint(reset.parent, reset.x, reset.y, p).x
        assertTrue(textRight <= resetLeft)
    }

    // ---- Use my computer IP ----

    fun `test clicking Use my computer IP invokes the callback`() {
        var invoked = 0
        val p = panel(onUseComputerIp = { invoked++ })
        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Disabled))

        p.useComputerIpLinkForTest.doClick()

        assertEquals(1, invoked)
    }

    fun `test resolving the computer IP disables the link and shows a resolving label`() {
        val p = panel()

        p.update(ProxyViewState(isDeviceEligible = true, readState = ProxyReadState.Disabled, isResolvingIp = true))

        assertFalse(p.useComputerIpLinkForTest.isEnabled)
        assertEquals("Resolving…", p.useComputerIpLinkForTest.text)
    }

    // ---- Recents ----

    fun `test selecting a recent row forwards it without changing the header state`() {
        var selected: ProxyEndpoint? = null
        val p = panel(onSelectRecent = { selected = it })
        val recent = endpoint("10.0.4.117", 8080)
        p.update(ProxyViewState(recents = listOf(recent)))

        p.recentsListForTest.selectedIndex = 0

        assertEquals(recent, selected)
    }
}

private fun recursivelyLayout(component: java.awt.Component) {
    if (component is java.awt.Container) {
        component.doLayout()
        component.components.forEach(::recursivelyLayout)
    }
}
