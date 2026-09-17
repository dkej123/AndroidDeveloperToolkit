package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarPanel
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.logcat.LogcatRowStyle

/**
 * Task 051's documented width x scale x theme comparison matrix, kept as executable assertions
 * rather than a static document so it can never drift from the supplied behavior.
 *
 * Source of truth for the width classes: `design/README.md`'s "Responsive rules" table, confirmed
 * against the interactive prototype's own breakpoint math (`design/designs/ADB Toolbox
 * Plugin.dc.html`: `compact = W < 340, wide = W >= 470`) and the code tokens
 * ([AdbToolboxTheme.Breakpoints]).
 *
 * | width class          | logcat timestamp | logcat tag | device bar serial | "N online" | facts grid |
 * |-----------------------|:---:|:---:|:---:|:---:|:---:|
 * | narrow (`< 340`)      | no  | no  | no  | "N" | 2 cols |
 * | dock (`340..469`)     | yes | no  | yes | "N online" | 3 cols |
 * | wide (`>= 470`)       | yes | yes | yes | "N online" | 3 cols |
 *
 * The 100%/200% scale axis is covered structurally, not by simulating a live IDE UI-scale factor
 * in a headless unit test (there is no supported way to do that here — see
 * [UnscaledBorderGuardTest]): every pixel measurement in [AdbToolboxTheme] is wrapped in
 * `JBUI.scale`/`JBUI.Borders`, so once a dimension is drawn from that object it is scale-correct by
 * construction; [UnscaledBorderGuardTest] guards against a raw literal reintroducing an unscaled
 * dimension. The theme axis (light/dark) is covered the same way the rest of this module already
 * covers it — see [AdbToolboxThemeTest] — by toggling [JBColor.setDark] and asserting supplied
 * light/dark variants; width-class behavior itself does not depend on theme, so this file exercises
 * both dark and light to confirm that holds.
 */
class ResponsiveScalingMatrixTest : BasePlatformTestCase() {

    private val narrow = AdbToolboxTheme.Breakpoints.narrow - 1
    private val dock = AdbToolboxTheme.Breakpoints.defaultDock
    private val wide = AdbToolboxTheme.Breakpoints.wide

    fun `test logcat column visibility matches the width x breakpoint matrix`() {
        assertColumns(narrow, timestamp = false, tag = false)
        assertColumns(dock, timestamp = true, tag = false)
        assertColumns(wide, timestamp = true, tag = true)
    }

    fun `test device bar identity fields match the width x breakpoint matrix in both themes`() {
        val wasDark = !JBColor.isBright()
        try {
            listOf(false, true).forEach { dark ->
                JBColor.setDark(dark)
                assertDeviceBar(narrow, expectedSerialShown = false, expectedOnlineCount = "3")
                assertDeviceBar(dock, expectedSerialShown = true, expectedOnlineCount = "3 online")
                assertDeviceBar(wide, expectedSerialShown = true, expectedOnlineCount = "3 online")
            }
        } finally {
            JBColor.setDark(wasDark)
        }
    }

    fun `test device facts grid column count matches the width x breakpoint matrix`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        panel.applyResponsiveLayout(narrow)
        assertEquals(2, panel.factColumnCount)

        panel.applyResponsiveLayout(dock)
        assertEquals(3, panel.factColumnCount)

        panel.applyResponsiveLayout(wide)
        assertEquals(3, panel.factColumnCount)
    }

    private fun assertColumns(width: Int, timestamp: Boolean, tag: Boolean) {
        val columns = LogcatRowStyle.columnsFor(width)
        assertEquals("timestamp at width=$width", timestamp, columns.timestamp)
        assertEquals("tag at width=$width", tag, columns.tag)
    }

    private fun assertDeviceBar(width: Int, expectedSerialShown: Boolean, expectedOnlineCount: String) {
        val serial = DeviceSerial.of("R58N90ABCDE")
        val device = Device(serial = serial, state = DeviceConnectionState.Online, model = "Pixel_5")
        val panel = DeviceContextBarPanel(onToggle = {}, onRefresh = {})

        panel.applyResponsiveLayout(width)
        panel.update(DeviceBarPresentation.Online(device, onlineCount = 3))

        assertEquals("serial at width=$width", if (expectedSerialShown) serial.toString() else "", panel.serialText)
        assertEquals("online count at width=$width", expectedOnlineCount, panel.onlineCountText)
    }
}
