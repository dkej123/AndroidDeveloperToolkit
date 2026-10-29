package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.domain.adb.*
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*

/** Includes the platform-owned root, not just the form returned by createCenterPanel. */
class DeepLinkWindowSurfaceTest : BasePlatformTestCase() {
    fun testIdeHeaderAndWindowBorderDoNotChangeTheDialogBoundsOrReappearAfterPacking() {
        val memory = object : DeviceDeepLinkMemory {
            override var lastPackage = ""
            override fun entries() = emptyList<RecentDeviceLink>()
            override fun replace(entries: List<RecentDeviceLink>) {}
        }
        val serial = DeviceSerial.of("R5CW72KKR4E")
        val form = DeviceDeepLinkPanel(serial, DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { _, _ -> }, {})
        val surface = DeviceDeepLinkContent(form, serial, "SM S911B", {}, {}, {})
        // DialogWrapper's own content, with its keyboard bindings, must remain attached.
        val content = JPanel(BorderLayout()).apply { isOpaque = false; add(surface) }
        val key = KeyStroke.getKeyStroke("ESCAPE")
        content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, "close-dialog")
        val platformHeader = JPanel().apply { preferredSize = Dimension(0, 30) }
        val platformWrapper = JPanel(BorderLayout()).apply {
            add(platformHeader, BorderLayout.NORTH); add(content, BorderLayout.CENTER)
        }
        val root = JRootPane().apply {
            border = BorderFactory.createLineBorder(Color.RED, 1)
            contentPane = platformWrapper
        }
        DeepLinkWindowSurface.install(root, content)
        fun layout(container: Container) {
            container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout)
        }
        repeat(3) {
            root.setSize(root.preferredSize)
            repeat(5) { layout(root) }
            assertEquals(surface.preferredSize, root.size)
            assertEquals(Point(0, 0), SwingUtilities.convertPoint(surface, Point(0, 0), root))
            assertEquals(surface.preferredSize, surface.size)
            assertSame(root, SwingUtilities.getRootPane(content))
            assertFalse(SwingUtilities.isDescendingFrom(platformHeader, root))
            assertEquals("close-dialog", content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(key))
        }
        val image = BufferedImage(root.width, root.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { root.printAll(graphics) } finally { graphics.dispose() }
        val file = Path.of("build", "deep-link-design", "complete-window-root.png")
        Files.createDirectories(file.parent); ImageIO.write(image, "png", file.toFile())
    }

    /**
     * The window is opaque in the surface colour; its corners are rounded natively. A per-pixel translucent
     * window made macOS recomposite the whole dialog on resizes and repaints: an intermittent flicker.
     */
    fun testWindowIsOpaqueInTheSurfaceColour() {
        assertEquals(255, DeepLinkWindowSurface.windowBackground().alpha)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme.Colors.panel, DeepLinkWindowSurface.windowBackground())
    }

    /**
     * Measured in Android Studio 2026.1 on macOS: DialogWrapper's show() marks the root pane with
     * fullWindowContent / transparentTitleBar and a 29px top inset even for an undecorated dialog,
     * which draws a native title-bar strip and square window corners around the rounded surface.
     */
    fun testPlatformTitleBarDecorationIsUndoneWheneverItIsApplied() {
        val root = JRootPane()
        DeepLinkWindowSurface.keepBorderless(root)
        root.putClientProperty("apple.awt.fullWindowContent", true)
        root.putClientProperty("apple.awt.transparentTitleBar", true)
        root.border = BorderFactory.createEmptyBorder(29, 0, 0, 0)
        assertEquals(false, root.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(false, root.getClientProperty("apple.awt.transparentTitleBar"))
        assertEquals(Insets(0, 0, 0, 0), root.insets)
    }

    /**
     * Even transparent, the macOS window rendered the corners outside the rounded surface (square shadow,
     * then black squares without it). The window itself is rounded natively to the surface's radius 8.
     */
    fun testNativeWindowCornersMatchTheSurfaceRadius() {
        assertEquals(com.intellij.ui.scale.JBUIScale.scale(8f), DeepLinkWindowSurface.cornerRadius())
        assertEquals(com.intellij.util.ui.JBUI.scale(16), DeepLinkWindowSurface.cornerArc())
    }
}
