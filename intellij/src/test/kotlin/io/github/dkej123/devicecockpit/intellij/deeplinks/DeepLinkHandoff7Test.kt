package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.domain.adb.*
import io.github.dkej123.devicecockpit.domain.deeplinks.AmStartOutput
import io.github.dkej123.devicecockpit.domain.packages.PackageEntry
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import io.github.dkej123.devicecockpit.intellij.ui.common.AndroidRobotIcon

/** design/DEEP_LINK_DIALOG.md handoff 7: handlers row, App Link warning, command toggle, result banner, Cold start. */
class DeepLinkHandoff7Test : BasePlatformTestCase() {
    private class Memory : DeviceDeepLinkMemory {
        override var lastPackage = ""
        override var coldStart = false
        override var commandExpanded = false
        var saved = listOf(RecentDeviceLink("acme://p/1", "com.acme.shop", 1), RecentDeviceLink("acme://p/2", "", 2))
        override fun entries() = saved
        override fun replace(entries: List<RecentDeviceLink>) { saved = entries }
    }
    private val serial = DeviceSerial.of("49060DLAQ002W7")
    private val apps = listOf(
        PackageEntry.unresolved("com.acme.shop").copy(label = "Acme Shop", isDebuggable = true),
        PackageEntry.unresolved("com.android.chrome").copy(label = "Chrome"),
    )

    private fun all(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) all(it) else emptyList() }
    private fun Container.named(name: String) = all(this).single { it.name == name }
    private fun panel(memory: Memory = Memory(), opened: MutableList<Pair<String, String>> = mutableListOf()) =
        DeviceDeepLinkPanel(serial, DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { uri, pkg -> opened += uri to pkg }, {}).apply { setPackages(apps) }

    fun testHandlersRowShowsEveryStateAndThePopoverPicksTheApp() {
        val panel = panel()
        val row = panel.named("deviceDeepLinkHandlers")
        assertFalse(row.isVisible)
        panel.showHandlers(HandlersState.Checking)
        assertTrue(row.isVisible)
        assertEquals("Checking which apps handle this link…", (panel.named("deviceDeepLinkHandlersText") as JLabel).text)
        val shop = "com.acme.shop/.deeplink.DeepLinkActivity"
        panel.showHandlers(HandlersState.Ready(LinkHandlers.Chooser(listOf(shop, "com.android.chrome/.Main"))))
        assertEquals("Chooser appears · 2 apps handle this link", (panel.named("deviceDeepLinkHandlersText") as JLabel).text)
        assertEquals("Show apps ▾", (panel.named("deviceDeepLinkHandlersLink") as AbstractButton).text)
        assertEquals("Checked with cmd package query-activities, without launching. Updates as you type.", (row as JComponent).toolTipText)
        panel.showHandlers(HandlersState.Ready(LinkHandlers.OpensDirectly(shop, listOf(shop))))
        assertEquals("Opens directly in", (panel.named("deviceDeepLinkHandlersText") as JLabel).text)
        assertEquals("Acme Shop", (panel.named("deviceDeepLinkHandlerApp") as JLabel).text)
        assertEquals(".deeplink.DeepLinkActivity", (panel.named("deviceDeepLinkHandlerActivity") as JLabel).text)
        assertFalse(panel.named("deviceDeepLinkHandlersLink").isVisible)
        panel.chooseFromHandlers("com.acme.shop")
        assertEquals("com.acme.shop", panel.selectedPackage)
        panel.showHandlers(HandlersState.Hidden)
        assertFalse(row.isVisible)
    }

    fun testAppLinkWarningOnlyForASpecificAppAndAWebLinkAndRealTapSwitchesToAnyApp() {
        val memory = Memory()
        val panel = panel(memory)
        val warning = panel.named("deviceDeepLinkAppLinkWarning")
        panel.selectPackage("com.acme.shop")
        panel.uriField.text = "acme://p/1"
        assertFalse(warning.isVisible)
        panel.uriField.text = "https://shop.acme.com/cart?coupon=SAVE20"
        assertTrue(warning.isVisible)
        assertTrue(all(warning as Container).filterIsInstance<JComponent>().any {
            (it as? javax.swing.text.JTextComponent)?.text?.contains("Open in Acme Shop delivers this link even if the App Link isn’t verified. On Android 12+ a real tap opens the browser unless shop.acme.com is verified.") == true
        })
        (panel.named("deviceDeepLinkRealTap") as AbstractButton).doClick()
        assertEquals("", panel.selectedPackage)
        assertEquals("", memory.lastPackage)
        assertFalse(warning.isVisible)
    }

    fun testCommandIsOneLineUntilExpandedAndRemembersTheChoiceAndColdStartAddsMinusS() {
        val memory = Memory()
        val panel = panel(memory)
        panel.uriField.text = "acme://p/1"
        assertEquals(1, panel.commandText().lines().size)
        val toggle = panel.named("deviceDeepLinkCommandToggle") as AbstractButton
        assertEquals("Expand", toggle.text)
        toggle.doClick()
        assertEquals("Collapse", toggle.text)
        assertTrue(memory.commandExpanded)
        assertTrue(panel.commandText().lines().size > 1)
        panel.coldStart = true
        assertTrue(panel.commandText().lines().any { it.trim() == "-S \\" })
        assertTrue(DeviceDeepLinkPanel(serial, DeviceDeepLinkUseCase(FakeAdbTransport(), memory) { 0 }, { _, _ -> }, {}).commandText().lines().size > 1)
    }

    fun testRunInRecentLoadsTheRowAndRunsIt() {
        val opened = mutableListOf<Pair<String, String>>()
        val panel = panel(opened = opened)
        val table = all(panel).filterIsInstance<javax.swing.JTable>().single()
        panel.runHistory(0)
        assertEquals("acme://p/2", table.getValueAt(0, 1))
        assertEquals(listOf("acme://p/2" to ""), opened)
        assertEquals("acme://p/2", panel.uriField.text)
        assertTrue(all(panel).filterIsInstance<JLabel>().none { it.text == "Click a row to load it into the form." })
        assertEquals("Click to load into the form · acme://p/2", panel.historyTooltip(0))
    }

    fun testResultBannerColdStartAndCloseRenderInBothThemes() {
        val wasDark = !com.intellij.ui.JBColor.isBright()
        try {
            for (dark in listOf(true, false)) {
                com.intellij.ui.JBColor.setDark(dark)
                val memory = Memory()
                val form = panel(memory)
                form.uriField.text = "acmeshop://product/42?ref=ide"
                form.selectPackage("com.acme.shop")
                form.showHandlers(HandlersState.Ready(LinkHandlers.HandledBy("com.acme.shop/.deeplink.DeepLinkActivity", listOf("com.acme.shop/.deeplink.DeepLinkActivity", "com.android.chrome/.Main"))))
                var dismissed = false
                var action: BannerAction? = null
                var copied = ""
                var coldStart: Boolean? = null
                var closed = false
                val content = DeviceDeepLinkContent(form, serial, "Pixel 9", {}, { closed = true }, {})
                content.onDismissResult = { dismissed = true }
                content.onResultAction = { action = it }
                content.onCopyOutput = { copied = it }
                content.onColdStartChanged = { coldStart = it }
                val raw = "Warning: Activity not started, intent has been delivered to currently running top-most instance.\nStatus: ok\nActivity: com.acme.shop/.deeplink.DeepLinkActivity\nComplete"
                val result = DeviceLinkResult.Opened(AmStartOutput.parse(raw), raw)
                content.showResult(LaunchBanner.of(result, serial, form.uriField.text, "com.acme.shop", false) { form.appLabel(it) }, 0, raw)
                assertEquals("Delivered to the running activity", (content.named("deviceDeepLinkResultTitle") as JLabel).text)
                (content.named("deviceDeepLinkResultAction") as AbstractButton).doClick()
                assertEquals(BannerAction.TurnOnColdStart, action)
                (content.named("deviceDeepLinkCopyOutput") as AbstractButton).doClick()
                assertEquals(raw, copied)
                (content.named("deviceDeepLinkColdStart") as AbstractButton).doClick()
                assertEquals(true, coldStart)
                content.setBusy(true)
                assertEquals("Opening…", content.openButton.text)
                assertFalse(content.openButton.isEnabled)
                assertTrue("The previous result stays while busy", content.named("deviceDeepLinkResult").isVisible)
                content.setBusy(false)
                assertEquals("Open on device", content.openButton.text)
                assertEquals("Close", (content.named("deviceDeepLinkCloseButton") as AbstractButton).text)
                assertEquals("Close (Esc)", (content.named("deviceDeepLinkClose") as JComponent).toolTipText)
                render(content, "h7-${if (dark) "dark" else "light"}-delivered")
                (content.named("deviceDeepLinkResultDismiss") as AbstractButton).doClick()
                assertTrue(dismissed)
                content.showResult(null, 0, "")
                assertFalse(content.named("deviceDeepLinkResult").isVisible)
                (content.named("deviceDeepLinkClose") as AbstractButton).doClick()
                assertTrue(closed)
            }
        } finally { com.intellij.ui.JBColor.setDark(wasDark) }
    }

    /** A Recent row replaces the URI in one step: no empty in-between state that shrinks and regrows the dialog. */
    fun testLoadingARecentRowIsOneEditWithoutAnEmptyUriInBetween() {
        val panel = panel()
        panel.uriField.text = "https://shop.acme.com/cart?coupon=SAVE20"
        val seen = mutableListOf<String>()
        var layouts = 0
        panel.onEdited = { seen += panel.uriField.text }
        panel.onLayoutChanged = { layouts++ }
        panel.loadHistory(RecentDeviceLink("acme://p/1?ref=ide", "com.acme.shop", 1))
        assertEquals(listOf("acme://p/1?ref=ide"), seen)
        assertEquals(listOf("ref" to "ide"), panel.queryEditor.values())
        assertTrue("Layout is requested a bounded number of times, not once per keystroke-like event: $layouts", layouts <= 2)
    }



    /** Expand grows the dialog; Collapse is a deliberate shrink, so the dialog returns to its collapsed height. */
    fun testCollapseReturnsTheDialogToItsHeightBeforeExpand() {
        val form = panel()
        val content = DeviceDeepLinkContent(form, serial, "SM S911B", {}, {}, {})
        fun height(): Int {
            content.setSize(content.preferredSize)
            fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
            repeat(4) { layout(content) }
            return content.preferredSize.height
        }
        form.uriField.text = "acme://p/1"
        val collapsed = height()
        val toggle = form.named("deviceDeepLinkCommandToggle") as AbstractButton
        toggle.doClick()
        val expanded = height()
        assertTrue("Expand grows: $collapsed -> $expanded", expanded > collapsed)
        toggle.doClick()
        assertEquals(collapsed, height())
    }

    /** Cold start only adds `-S` to the one-line command: the dialog must not need a new size. */
    fun testColdStartDoesNotChangeTheDialogHeight() {
        val form = panel()
        val content = DeviceDeepLinkContent(form, serial, "SM S911B", {}, {}, {})
        fun height(): Int {
            content.setSize(content.preferredSize)
            fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
            repeat(4) { layout(content) }
            return content.preferredSize.height
        }
        form.uriField.text = "acme://p/1"
        val before = height()
        var layouts = 0
        form.onLayoutChanged = { layouts++ }
        (content.named("deviceDeepLinkColdStart") as AbstractButton).doClick()
        assertTrue(form.commandText().contains(" -S "))
        assertEquals(before, height())
        assertEquals("One layout request for one click", 1, layouts)
    }

    /**
     * The dialog is exactly as tall as its content: no space reserved for a hidden handlers row, parameter row or
     * banner (user feedback, 2026-10-29). It grows and shrinks with the content; the window resizes in the same
     * event (DeviceDeepLinkDialog.fitToContent), which is what removed the flicker.
     */
    fun testDialogHeightFollowsTheContentWithoutReservedSpace() {
        val memory = Memory().apply { saved = listOf(RecentDeviceLink("https://exmaple.com?test=test2", "", 5), RecentDeviceLink("https://exmaple4.com", "", 4)) }
        val form = panel(memory)
        val content = DeviceDeepLinkContent(form, serial, "SM S911B", {}, {}, {})
        fun height(): Int {
            content.setSize(content.preferredSize)
            fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
            repeat(4) { layout(content) }
            return content.preferredSize.height
        }
        assertFalse("A hidden handlers row takes no space", form.handlersRow.isVisible)
        val empty = height()
        form.loadHistory(memory.saved[0])
        form.showHandlers(HandlersState.Checking)
        val withParameters = height()
        form.loadHistory(memory.saved[1])
        val withoutParameters = height()
        assertTrue("Grows for the handlers row and parameters: $empty -> $withParameters", withParameters > empty)
        assertTrue("Shrinks back without parameters: $withParameters -> $withoutParameters", withoutParameters < withParameters)
        val toggle = form.named("deviceDeepLinkCommandToggle") as AbstractButton
        toggle.doClick(); toggle.doClick()
        assertEquals("Expand then Collapse returns to the same height", withoutParameters, height())
    }

    /**
     * After Open the banner's wrapped detail made the content taller than the window that had just been fitted:
     * GridBagLayout then fell back to minimum sizes (the Recent table collapsed to a strip) and centred the rest
     * (an empty band above the title). Short of height, the form must keep every row's height, anchored at the top.
     */
    fun testShortOfHeightRowsKeepTheirSizeAndStayAtTheTop() {
        val memory = Memory()
        val form = panel(memory)
        val content = DeviceDeepLinkContent(form, serial, "SM S911B", {}, {}, {})
        fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
        content.setSize(content.preferredSize); repeat(4) { layout(content) }
        val table = all(form).filterIsInstance<javax.swing.JTable>().single()
        val tableBox = table.parent.parent.parent as JComponent
        val fullHeight = tableBox.height
        val title = all(content).filterIsInstance<JLabel>().first { it.text == "Open deep link" }
        val titleY = javax.swing.SwingUtilities.convertPoint(title, 0, 0, content).y
        content.setSize(content.width, content.preferredSize.height - com.intellij.util.ui.JBUI.scale(40)); repeat(4) { layout(content) }
        assertEquals("The Recent table keeps its height", fullHeight, tableBox.height)
        assertEquals("The title stays at the top", titleY, javax.swing.SwingUtilities.convertPoint(title, 0, 0, content).y)
    }

    /** "Any app" and apps the plugin has no icon for (system apps such as Chrome) show the Android robot (user request). */
    fun testAnyAppAndUnknownSystemAppsShowTheAndroidRobot() {
        val form = panel()
        assertTrue(form.tileIcon("") is AndroidRobotIcon)
        assertTrue("An app outside the user-app list", form.tileIcon("com.google.android.gm") is AndroidRobotIcon)
        assertFalse("Known apps keep their own tile", form.tileIcon("com.acme.shop") is AndroidRobotIcon)
        assertEquals(com.intellij.util.ui.JBUI.scale(14), form.tileIcon("", 14).iconWidth)
        val image = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics(); try { AndroidRobotIcon(32).paintIcon(null, g, 0, 0) } finally { g.dispose() }
        assertTrue("The robot is painted", (0 until 32).any { x -> (0 until 32).any { y -> image.getRGB(x, y) ushr 24 != 0 } })
    }

    /** An Any app row shows the app that really took the link last time, with its icon (user request). */
    fun testAnyAppRowShowsTheAppThatOpenedIt() {
        val memory = Memory().apply {
            saved = listOf(
                RecentDeviceLink("ionitystage://map", "", 2, handledBy = "com.acme.shop"),
                RecentDeviceLink("geo:1,2", "", 1),
            )
        }
        val form = panel(memory)
        val table = all(form).filterIsInstance<javax.swing.JTable>().single()
        assertEquals("geo:1,2", table.getValueAt(0, 1))
        assertEquals("Any app", table.getValueAt(0, 2))
        assertEquals("Acme Shop", table.getValueAt(1, 2))
        assertFalse(form.historyTile(1) is AndroidRobotIcon)
        assertTrue(form.historyTile(0) is AndroidRobotIcon)
    }

    private fun render(content: DeviceDeepLinkContent, name: String) {
        val previous = com.intellij.ui.scale.JBUIScale.scale(1f)
        content.setSize(content.preferredSize)
        fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
        repeat(6) { layout(content) }
        val image = java.awt.image.BufferedImage(content.width, content.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { content.printAll(g) } finally { g.dispose() }
        val file = java.nio.file.Path.of("build", "deep-link-design", "$name.png")
        java.nio.file.Files.createDirectories(file.parent)
        javax.imageio.ImageIO.write(image, "png", file.toFile())
        assertEquals(previous, com.intellij.ui.scale.JBUIScale.scale(1f))
    }
}
