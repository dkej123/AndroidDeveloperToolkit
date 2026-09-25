package dev.acme.adbtoolbox.intellij.devicefacts

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.application.devicefacts.DeviceSectionMeta
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactValue
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsSnapshot
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

private val serial = DeviceSerial.of("R58N90ABCDE")

/**
 * [DeviceFactsPanel] is the minimal, unstyled Device-view facts binding (task 015). Kept as a
 * [BasePlatformTestCase] like every other test in this module — see `AdbTransportSelectionTest`'s
 * class doc for why a plain JUnit 5 test class here is not discovered by `:intellij:test`'s runner.
 */
class DeviceFactsPanelTest : BasePlatformTestCase() {

    private fun Component.descendants(): List<Component> {
        val result = mutableListOf<Component>()
        fun visit(component: Component) {
            result.add(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(this)
        return result
    }

    private fun DeviceFactsPanel.visibleTexts(): Set<String> = descendants()
        .filter { component -> component.isVisible && hasVisibleAncestors(component, this) }
        .mapNotNull {
            when (it) {
                is AbstractButton -> it.text
                is JLabel -> it.accessibleContext.accessibleName ?: it.text
                is javax.swing.text.JTextComponent -> it.text
                else -> null
            }
        }
        .filter { it.isNotBlank() }
        .toSet()

    private fun hasVisibleAncestors(component: Component, root: Component): Boolean {
        var parent = component.parent
        while (parent != null && parent !== root) {
            if (!parent.isVisible) return false
            parent = parent.parent
        }
        return true
    }

    fun `test the Copy report button is disabled until a snapshot exists`() {
        var copyClicked = false
        val panel = DeviceFactsPanel(onCopyReport = { copyClicked = true })

        assertFalse(panel.copyReportButton.isEnabled)

        panel.update(DeviceFactsViewState.NoDevice)
        assertFalse(panel.copyReportButton.isEnabled)

        val snapshot = DeviceFactsSnapshot.loading(serial)
        panel.update(DeviceFactsViewState.Partial(snapshot))
        assertTrue(panel.copyReportButton.isEnabled)

        panel.copyReportButton.doClick()
        assertTrue(copyClicked)
    }

    fun `test connected content exposes the three supplied sections and dedicated feature slots`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        panel.update(DeviceFactsViewState.Partial(DeviceFactsSnapshot.loading(serial)))

        assertContainsElements(panel.visibleTexts(), "Mirroring", "Capture", "Device", "Copy report")
        assertNotSame(panel.mirroringSlot, panel.captureSlot)
        assertNotSame(panel.captureSlot, panel.deviceActionsSlot)
    }

    fun `test section headers show the resolved scrcpy version and capture directory`() {
        // Regression (docs/e2e-testing.md): the headers were fixed "scrcpy 2.7" and "~/Desktop".
        val panel = DeviceFactsPanel(onCopyReport = {})
        panel.update(DeviceFactsViewState.Partial(DeviceFactsSnapshot.loading(serial)))

        panel.updateSectionMeta(DeviceSectionMeta(mirroring = "scrcpy 4.1", capture = "~/captures"))

        assertContainsElements(panel.visibleTexts(), "scrcpy 4.1", "~/captures")
        assertDoesntContain(panel.visibleTexts(), "scrcpy 2.7", "~/Desktop")
    }

    fun `test no-device state replaces content with the supplied empty state actions and copy`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        panel.update(DeviceFactsViewState.NoDevice)

        assertContainsElements(
            panel.visibleTexts(),
            "No device connected",
            "Connect over USB with USB debugging enabled, or pair wirelessly. Actions stay disabled until a device is online.",
            "Refresh",
            "Pair over Wi-Fi…",
        )
        assertFalse(panel.visibleTexts().contains("Mirroring"))
    }

    fun `test loading state replaces content with six skeleton bars`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        panel.update(DeviceFactsViewState.Loading)

        assertEquals(6, panel.visibleSkeletonCount)
        assertFalse(panel.visibleTexts().contains("Device"))
    }

    fun `test facts grid changes from three to two columns at the narrow breakpoint`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        panel.applyResponsiveLayout(380)
        assertEquals(3, panel.factColumnCount)

        panel.applyResponsiveLayout(300)
        assertEquals(2, panel.factColumnCount)
    }

    fun `test no-device copy and actions fit the narrow content viewport`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        panel.update(DeviceFactsViewState.NoDevice)
        panel.setSize(266, 568)
        repeat(3) { recursivelyLayout(panel) }

        val visibleContent = panel.descendants()
            .filter { it.isVisible && hasVisibleAncestors(it, panel) }
            .filter {
                (it is AbstractButton && it.text in setOf("Refresh", "Pair over Wi-Fi…")) ||
                    (it is javax.swing.text.JTextComponent && it.text.startsWith("Connect over USB"))
            }

        assertEquals(3, visibleContent.size)
        visibleContent.forEach { component ->
            val bounds = javax.swing.SwingUtilities.convertRectangle(component.parent, component.bounds, panel)
            assertTrue("${component.javaClass.simpleName} has no paintable area: $bounds", bounds.width > 0 && bounds.height > 0)
            assertTrue("${component.javaClass.simpleName} starts outside the viewport: $bounds", bounds.x >= 0)
            assertTrue("${component.javaClass.simpleName} exceeds the viewport: $bounds", bounds.x + bounds.width <= panel.width)
        }
        val body = visibleContent.single { it is javax.swing.text.JTextComponent }
        assertTrue(
            "Empty-state copy must retain the design's 16px side margins: ${body.bounds}",
            body.width <= panel.width - 32,
        )
    }

    fun `test rendering a Connected snapshot never fails even when one fact is Unavailable`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        val snapshot = DeviceFactsSnapshot(
            serial = serial,
            facts = mapOf(
                DeviceFactId.AndroidVersion to DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35)),
                DeviceFactId.Resolution to DeviceFactState.Unavailable("malformed"),
                DeviceFactId.Density to DeviceFactState.Available(DeviceFactValue.Density(420)),
                DeviceFactId.Battery to DeviceFactState.Available(DeviceFactValue.Battery(72, charging = true)),
                DeviceFactId.Abi to DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a")),
                DeviceFactId.Uptime to DeviceFactState.Loading,
            ),
        )

        panel.update(DeviceFactsViewState.Connected(snapshot))

        assertTrue(panel.copyReportButton.isEnabled)
    }
}

private fun recursivelyLayout(component: Component) {
    if (component is Container) {
        component.doLayout()
        component.components.forEach(::recursivelyLayout)
    }
}
