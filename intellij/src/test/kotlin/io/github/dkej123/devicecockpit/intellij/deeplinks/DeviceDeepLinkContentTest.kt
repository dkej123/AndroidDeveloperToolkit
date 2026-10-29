package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.domain.adb.*
import io.github.dkej123.devicecockpit.domain.packages.PackageEntry
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Renders the very same complete surface used by DialogWrapper, including the header and footer. */
class DeviceDeepLinkContentTest : BasePlatformTestCase() {
    private class Memory(val recent: List<RecentDeviceLink>) : DeviceDeepLinkMemory {
        override var lastPackage = "com.acme.driver"
        override fun entries() = recent
        override fun replace(entries: List<RecentDeviceLink>) {}
    }
    fun testCompleteDialogMatchesDesignDimensionsWithAndWithoutHistoryAndParameters() {
        val wasDark = JBColor.isBright().not()
        val priorScale = com.intellij.ui.scale.JBUIScale.scale(1f)
        com.intellij.ui.scale.JBUIScale.setUserScaleFactor(2f)
        try {
            for (dark in listOf(true, false)) for (history in listOf(false, true)) for (parameters in listOf(false, true)) {
                JBColor.setDark(dark)
                val recent = if (history) (1..12).map { index ->
                    RecentDeviceLink("acmeshop://product/$index?ref=ide", if (index % 3 == 0) "" else "com.acme.shop.debug", java.time.LocalDate.now().atTime(12, 40).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                } else emptyList()
                val form = DeviceDeepLinkPanel(DeviceSerial.of("49060DLAQ002W7"), DeviceDeepLinkUseCase(FakeAdbTransport(), Memory(recent)) { 0 }, { _, _ -> }, {})
                form.setPackages(listOf(
                    PackageEntry.unresolved("com.acme.driver").copy(label = "Acme Driver", isDebuggable = true),
                    PackageEntry.unresolved("com.acme.shop.debug").copy(label = "Acme Shop (debug)", isDebuggable = true),
                ))
                if (parameters) form.queryEditor.addParameter()
                var cleared = false
                val content = DeviceDeepLinkContent(form, DeviceSerial.of("49060DLAQ002W7"), "Pixel 9", { form.clearForm(); cleared = true }, {}, {})
                content.setSize(content.preferredSize)
                fun layout(container: Container) {
                    container.doLayout()
                    container.components.filterIsInstance<Container>().forEach(::layout)
                }
                repeat(6) { layout(content) }
                assertEquals(com.intellij.util.ui.JBUI.scale(600), content.width)
                assertTrue("Content height must be driven by the visible sections", content.height < com.intellij.util.ui.JBUI.scale(if (history) 800 else 550))
                assertEquals(com.intellij.util.ui.JBUI.scale(24), content.openButton.height)
                assertTrue("Footer must stay immediately below the form", content.height - content.openButton.y > 0)
                val image = BufferedImage(content.width, content.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try { content.printAll(graphics) } finally { graphics.dispose() }
                val file = Path.of("build", "deep-link-design", "${if (dark) "dark" else "light"}-${if (history) "history" else "empty"}-${if (parameters) "parameters" else "default"}.png")
                Files.createDirectories(file.parent)
                ImageIO.write(image, "png", file.toFile())
                form.uriField.text = "https://example.com?x=1"
                content.clearButton.doClick()
                assertTrue(cleared)
                assertEquals("", form.uriField.text)
                assertTrue(form.queryEditor.values().isEmpty())
            }
        } finally { JBColor.setDark(wasDark); com.intellij.ui.scale.JBUIScale.setUserScaleFactor(priorScale) }
    }
}
