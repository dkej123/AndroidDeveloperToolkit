package io.github.dkej123.devicecockpit.intellij.inspector

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.layout.InspectorCapture
import io.github.dkej123.devicecockpit.application.layout.LayoutInspectorState
import io.github.dkej123.devicecockpit.application.layout.LayoutSnapshot
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.layout.UiAutomatorXmlParser
import io.github.dkej123.devicecockpit.domain.layout.UiHierarchy
import io.github.dkej123.devicecockpit.domain.layout.UiTreeParse
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** A capture of the API 30 Settings screen with a synthetic screenshot that paints every node. */
internal fun settingsCapture(black: Boolean = false): InspectorCapture {
    val root = (UiAutomatorXmlParser.parse(LayoutInspectorPanelTest::class.java.getResource("/layout/api30-settings.xml")!!.readText()) as UiTreeParse.Parsed).root
    val image = BufferedImage(1080, 2072, BufferedImage.TYPE_INT_RGB)
    if (!black) {
        val g = image.createGraphics()
        g.color = Color(0xF7F2FA)
        g.fillRect(0, 0, 1080, 2072)
        root.descendantsAndSelf().filter { it.flags.clickable }.forEach { n ->
            g.color = Color(0xE8DEF8)
            g.fillRoundRect(n.bounds.left + 8, n.bounds.top + 8, n.bounds.width - 16, n.bounds.height - 16, 40, 40)
        }
        g.color = Color(0x1D1B20)
        g.font = g.font.deriveFont(44f)
        root.descendantsAndSelf().filter { it.text.isNotEmpty() }.forEach { n -> g.drawString(n.text, n.bounds.left, n.bounds.bottom - 12) }
        g.dispose()
    }
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    return InspectorCapture(LayoutSnapshot(png, UiHierarchy(root, 440)), DeviceSerial.of("emulator-5554"), "Pixel 9", "com.android.settings/.homepage.SettingsHomepageActivity", 1_791_380_671_000)
}

class LayoutInspectorPanelTest : BasePlatformTestCase() {
    private fun panel() = LayoutInspectorPanel(onRecapture = {}, onOpenQuickToggles = {}, chooseOverlayFile = { null }).apply { setSize(1300, 820) }

    fun `test a capture fills the meta, tree footer and canvas`() {
        val p = panel()
        p.update(LayoutInspectorState(capture = settingsCapture(), deviceOnline = true, deviceName = "Pixel 9"))

        assertTrue(p.metaForTest.startsWith("Pixel 9 · "))
        assertTrue(p.metaForTest.endsWith("com.android.settings/.homepage.SettingsHomepageActivity · 1080×2072 · 440 dpi"))
        assertEquals("83 nodes · uiautomator", p.treeFooterForTest)
        assertNotNull(p.canvasForTest.hierarchy)
        assertFalse(p.canvasForTest.secure)
    }

    fun `test selecting an element shows its attributes in dp and px`() {
        val p = panel()
        val capture = settingsCapture()
        p.update(LayoutInspectorState(capture = capture, deviceOnline = true))
        val display = capture.snapshot.hierarchy.root.descendantsAndSelf().first { it.text == "Display" }

        p.selectForTest(display)

        val rows = p.attributesForTest.rowsForTest
        assertEquals("Display", rows["text"])
        assertEquals("[198,1539][342,1598]", rows["bounds (px)"])
        assertEquals("x 72  y 560  ·  52 × 21", rows["bounds (dp)"])
        assertEquals("≈ 18 sp (estimated from glyph height)", rows["text size"])
    }

    fun `test hovering another element reports the distance in the status bar`() {
        val p = panel()
        val capture = settingsCapture()
        p.update(LayoutInspectorState(capture = capture, deviceOnline = true))
        val nodes = capture.snapshot.hierarchy.root.descendantsAndSelf().toList()
        p.selectForTest(nodes.first { it.text == "Display" })

        p.hoverForTest(nodes.first { it.text == "Battery" })

        assertEquals("TextView → TextView · 51 dp", p.messageForTest)
    }

    fun `test the accessibility mode lists stops with summary cards and a Markdown report`() {
        val p = panel()
        p.update(LayoutInspectorState(capture = settingsCapture(), deviceOnline = true))
        p.auditChipForTest.doClick()

        assertTrue(p.canvasForTest.auditMode)
        val report = p.auditPanelForTest.reportForTest()
        assertTrue(report.startsWith("# Accessibility audit — com.android.settings/.homepage.SettingsHomepageActivity"))
        assertTrue(report.contains("| 1 | Search settings"))
        assertEquals(3, p.auditPanelForTest.cardTextsForTest.size)
    }

    fun `test tree search keeps ancestors of matches and says when nothing matches`() {
        val p = panel()
        p.update(LayoutInspectorState(capture = settingsCapture(), deviceOnline = true))

        p.treeSearchForTest.text = "Battery"
        assertTrue(p.treeForTest.rowCount in 2..30)
        p.treeSearchForTest.text = "no such thing"
        assertEquals(1, p.treeForTest.rowCount)
    }

    fun `test the device-gone, secure and error states`() {
        val p = panel()
        p.update(LayoutInspectorState(capture = settingsCapture(), deviceGone = true))
        assertTrue(p.bannerForTest!!.contains("Pixel 9 disconnected — this capture stays readable."))

        p.update(LayoutInspectorState(capture = settingsCapture(black = true), deviceOnline = true))
        assertTrue(p.canvasForTest.secure)
        assertTrue(p.bannerForTest!!.contains("marks this screen secure"))

        val fresh = panel()
        fresh.update(LayoutInspectorState(error = "uiautomator dump: ERROR: could not get idle state.", deviceOnline = true))
        assertEquals("Couldn’t capture the layout", fresh.stateTitleForTest)
    }

    fun `test render for review`() {
        val p = panel()
        p.update(LayoutInspectorState(capture = settingsCapture(), deviceOnline = true, deviceName = "Pixel 9"))
        val nodes = settingsCapture().snapshot.hierarchy.root.descendantsAndSelf().toList()
        p.gridChipForTest.doClick()
        p.selectForTest(nodes.first { it.text == "Display" })
        p.canvasForTest.hovered = nodes.first { it.text == "Battery" }
        settle(p)
        val image = BufferedImage(1300, 820, BufferedImage.TYPE_INT_RGB)
        p.paint(image.createGraphics())
        val out = Path.of(System.getProperty("user.dir"), "build", "reports", "visual")
        Files.createDirectories(out)
        ImageIO.write(image, "png", out.resolve("inspector-review.png").toFile())
        p.auditChipForTest.doClick()
        settle(p)
        val audit = BufferedImage(1300, 820, BufferedImage.TYPE_INT_RGB)
        p.paint(audit.createGraphics())
        ImageIO.write(audit, "png", out.resolve("inspector-audit-review.png").toFile())
    }

    fun `test the canvas fits the screen and the side panels take their design widths`() {
        val p = panel()
        p.update(LayoutInspectorState(capture = settingsCapture(), deviceOnline = true))

        settle(p)

        assertTrue("zoom ${p.canvasForTest.effectiveZoom}", p.canvasForTest.effectiveZoom < 1.0)
        assertTrue(p.zoomTextForTest, p.zoomTextForTest.startsWith("Fit · "))
        assertEquals(260, p.treePanelForTest.width, 6)
        assertEquals(300, p.rightPanelForTest.width, 6)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)

    /** Lays the panel out and lets the queued resize events (Fit, responsive widths) run, as on screen. */
    private fun settle(p: LayoutInspectorPanel) {
        // Offscreen, revalidate() and validate() do nothing (no peer), so wrapped text never gets its second pass: force it.
        repeat(3) {
            invalidateAll(p)
            layoutAll(p)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        }
    }

    private fun invalidateAll(c: java.awt.Component) {
        c.invalidate()
        (c as? java.awt.Container)?.components?.forEach(::invalidateAll)
    }

    private fun layoutAll(c: java.awt.Component) {
        c.doLayout()
        (c as? java.awt.Container)?.components?.forEach(::layoutAll)
    }
}
