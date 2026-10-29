package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.domain.adb.*
import io.github.dkej123.devicecockpit.domain.packages.PackageEntry

class DeviceDeepLinkPanelTest : BasePlatformTestCase() {
    private class Memory : DeviceDeepLinkMemory {
        override var lastPackage = "com.remembered"
        var saved = listOf(RecentDeviceLink("test://history", "com.history", 123))
        override fun entries() = saved
        override fun replace(entries: List<RecentDeviceLink>) { saved = entries }
    }
    fun testEmptyFormShowsTheCommandTemplateAndThePreviewKeepsAdbAndSerialOnOneLine() {
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("49060DLAQ002W7"), DeviceDeepLinkUseCase(FakeAdbTransport(), Memory()) { 0 }, { _, _ -> }, {})
        fun descendants(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap {
            listOf(it) + if (it is java.awt.Container) descendants(it) else emptyList()
        }
        val preview = descendants(panel).filterIsInstance<javax.swing.JTextArea>().single { it.name == "deviceDeepLinkCommand" }
        assertTrue("Empty URI must still show the command template", preview.text.contains("'<uri>'"))
        assertTrue(preview.text.lineSequence().first().contains("adb -s 49060DLAQ002W7 shell am start"))
        panel.uriField.text = "https://example.com/path?x=1&y=2"
        assertTrue(preview.text.lineSequence().first().contains("adb -s 49060DLAQ002W7 shell am start"))
        assertEquals("Collapsed to one line by default (handoff 7)", 1, preview.text.lines().size)
        assertTrue(preview.text.contains("-d 'https://example.com/path?x=1&y=2'"))
    }

    fun testQueryRowsAreOptInAndStaySynchronizedWithTheUriAndClear() {
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), Memory()) { 0 }, { _, _ -> }, {})
        assertTrue(panel.queryEditor.values().isEmpty())
        panel.queryEditor.addParameter()
        assertEquals(listOf("" to ""), panel.queryEditor.values())
        fun all(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is java.awt.Container) all(it) else emptyList() }
        panel.uriField.text = "test://item?ref=ide#details"
        assertEquals(listOf("ref" to "ide"), panel.queryEditor.values())
        val value = all(panel).filterIsInstance<DeepLinkField>().single { it.name == "deviceDeepLinkQueryValue:0" }
        value.text = "Łódź & QA"
        assertEquals("test://item?ref=%C5%81%C3%B3d%C5%BA%20%26%20QA#details", panel.uriField.text)
        assertTrue(panel.commandText().contains("%20%26%20"))
        panel.clearForm()
        assertEquals("", panel.uriField.text)
        assertTrue(panel.queryEditor.values().isEmpty())
        assertTrue(panel.commandText().contains("'<uri>'"))
    }

    fun testQueryParametersShowTheDesignEmptyStateUntilARowExists() {
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), Memory()) { 0 }, { _, _ -> }, {})
        fun all(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is java.awt.Container) all(it) else emptyList() }
        fun named(name: String) = all(panel.queryEditor).single { it.name == name }
        assertTrue(named("deviceDeepLinkQueryEmpty").isVisible)
        assertFalse(named("deviceDeepLinkQueryRows").isVisible)
        panel.uriField.text = "test://item?ref=ide"
        assertFalse(named("deviceDeepLinkQueryEmpty").isVisible)
        assertTrue(named("deviceDeepLinkQueryRows").isVisible)
        assertTrue("Keys are bold like the prototype", all(panel).filterIsInstance<DeepLinkField>().single { it.name == "deviceDeepLinkQueryKey:0" }.font.isBold)
        panel.uriField.text = "test://item"
        assertTrue(named("deviceDeepLinkQueryEmpty").isVisible)
    }

    fun testAppFilterMatchesLabelOrPackageAndAlsoFiltersAnyApp() {
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), Memory()) { 0 }, { _, _ -> }, {})
        panel.setPackages(listOf(
            PackageEntry.unresolved("com.acme.driver").copy(label = "Acme Driver"),
            PackageEntry.unresolved("org.example.mail").copy(label = "Mail"),
        ))
        assertEquals(listOf("", "com.acme.driver", "org.example.mail"), panel.appChoices("").map { it.packageName })
        assertEquals(listOf("com.acme.driver"), panel.appChoices("DRIVER").map { it.packageName })
        assertEquals(listOf("org.example.mail"), panel.appChoices("example").map { it.packageName })
        assertEquals(listOf(""), panel.appChoices("any").map { it.packageName })
        assertTrue(panel.appChoices("nothing").isEmpty())
    }

    fun testASuccessfulLaunchShowsUpInTheOpenDialogsHistory() {
        val memory = Memory().apply { saved = emptyList() }
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { _, _ -> }, {})
        fun all(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is java.awt.Container) all(it) else emptyList() }
        val table = all(panel).filterIsInstance<javax.swing.JTable>().single()
        assertFalse("No history yet, so the section is hidden", table.parent.parent.parent.isVisible)
        panel.uriField.text = "test://kept"
        memory.saved = listOf(RecentDeviceLink("test://kept", "", 1))
        panel.refreshHistory()
        assertEquals(1, table.rowCount)
        assertTrue(table.parent.parent.parent.isVisible)
        assertTrue(all(panel).filterIsInstance<javax.swing.JLabel>().any { it.text == "RECENT · 1" && it.isVisible })
        assertEquals("The form keeps the link so it can be opened again", "test://kept", panel.uriField.text)
    }

    fun testNewestHistoryIsTheLastRowAndTheTableOpensScrolledToIt() {
        // The store keeps newest first; the table shows oldest at the top, newest at the bottom.
        val memory = Memory().apply { saved = (12 downTo 1).map { RecentDeviceLink("test://item/$it", "", it.toLong()) } }
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { _, _ -> }, {})
        fun all(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is java.awt.Container) all(it) else emptyList() }
        val table = all(panel).filterIsInstance<javax.swing.JTable>().single()
        assertEquals("test://item/1", table.getValueAt(0, 1))
        assertEquals("test://item/12", table.getValueAt(11, 1))
        panel.setSize(562, panel.preferredSize.height)
        fun layout(container: java.awt.Container) {
            container.doLayout()
            container.components.filterIsInstance<java.awt.Container>().forEach(::layout)
        }
        repeat(4) { layout(panel) }
        panel.scrollHistoryToNewest()
        val viewport = table.parent as javax.swing.JViewport
        assertTrue("The newest row is visible", viewport.viewRect.contains(table.getCellRect(11, 1, true)))
        assertFalse("The oldest row is scrolled away", viewport.viewRect.contains(table.getCellRect(0, 1, true)))
        val loaded = mutableListOf<String>()
        panel.loadHistory(memory.saved.first()).also { loaded += panel.uriField.text }
        assertEquals(listOf("test://item/12"), loaded)
    }

    fun testTwelveHistoryEntriesScrollInsideABoundedTable() {
        val memory = Memory().apply { saved = (1..12).map { RecentDeviceLink("acmeshop://product/$it?ref=ide", if (it % 2 == 0) "com.example" else "", it.toLong()) } }
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { _, _ -> }, {})
        panel.setPackages(listOf(PackageEntry.unresolved("com.example").copy(label = "Example Shop")))
        panel.setSize(562, panel.preferredSize.height)
        fun layout(container: java.awt.Container) {
            container.doLayout()
            container.components.filterIsInstance<java.awt.Container>().forEach(::layout)
        }
        repeat(4) { layout(panel) }
        fun all(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is java.awt.Container) all(it) else emptyList() }
        val table = all(panel).filterIsInstance<javax.swing.JTable>().single()
        assertEquals(12, table.rowCount)
        assertTrue(table.parent.height <= com.intellij.util.ui.JBUI.scale(230))
        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { panel.printAll(graphics) } finally { graphics.dispose() }
        val output = java.nio.file.Path.of("build", "deep-link-preview.png")
        java.nio.file.Files.createDirectories(output.parent)
        javax.imageio.ImageIO.write(image, "png", output.toFile())
    }

    fun testHistoryLoadsWithoutLaunchingAndPreviewTracksTheForm() {
        val memory = Memory()
        val opened = mutableListOf<Pair<String, String>>()
        val useCase = DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), useCase, { uri, pkg -> opened += uri to pkg }, {})
        panel.loadHistory(memory.saved.first())
        assertEquals("test://history", panel.uriField.text)
        assertEquals("com.history", panel.selectedPackage)
        assertTrue(opened.isEmpty())
        assertTrue(panel.commandText().endsWith("-p com.history"))
        panel.uriField.text = "test://edited?a=1&b=2"
        assertTrue(panel.commandText().contains("test://edited?a=1&b=2"))
    }
    fun testMissingRememberedAppFallsBackWithoutErasingPreferenceAndClearRestoresDefault() {
        val memory = Memory()
        val panel = DeviceDeepLinkPanel(DeviceSerial.of("s"), DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { _, _ -> }, {})
        panel.setPackages(listOf(PackageEntry.unresolved("com.other")))
        assertEquals("", panel.selectedPackage)
        assertEquals("com.remembered", memory.lastPackage)
        panel.loadHistory(memory.saved.first())
        panel.clearForm()
        assertEquals("", panel.uriField.text)
        assertEquals("", panel.selectedPackage)
        panel.setPackages(listOf(PackageEntry.unresolved("com.remembered")))
        panel.clearForm()
        assertEquals("com.remembered", panel.selectedPackage)
    }
}
