package io.github.dkej123.devicecockpit.intellij.feedback

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackAction
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import javax.swing.JButton

/** [ToastStackPanel] renders task 013's bounded toast list. Kept as a [BasePlatformTestCase] like every other test in this module. */
class ToastStackPanelTest : BasePlatformTestCase() {

    private fun message(id: String, action: FeedbackAction? = null) =
        FeedbackMessage(id = id, text = "text-$id", severity = FeedbackSeverity.Info, action = action)

    private fun buttonsOf(container: java.awt.Container): List<JButton> =
        container.components.flatMap { child ->
            val nested = if (child is java.awt.Container) buttonsOf(child) else emptyList()
            (if (child is JButton) listOf(child) else emptyList()) + nested
        }

    fun `test an empty toast list renders no rows`() {
        val panel = ToastStackPanel(onAction = {}, onDismiss = {})

        panel.update(emptyList())

        assertEquals(0, panel.renderedIds.size)
    }

    fun `test each toast renders as one row in order`() {
        val panel = ToastStackPanel(onAction = {}, onDismiss = {})

        panel.update(listOf(message("a"), message("b"), message("c")))

        assertEquals(listOf("a", "b", "c"), panel.renderedIds)
    }

    fun `test updating with a new list replaces the rows rather than appending`() {
        val panel = ToastStackPanel(onAction = {}, onDismiss = {})
        panel.update(listOf(message("a"), message("b")))

        panel.update(listOf(message("b"), message("c")))

        assertEquals(listOf("b", "c"), panel.renderedIds)
    }

    fun `test clicking a toast's dismiss button invokes onDismiss with its id`() {
        var dismissedId: String? = null
        val panel = ToastStackPanel(onAction = {}, onDismiss = { id -> dismissedId = id })
        panel.update(listOf(message("a")))

        val row = panel.getComponent(0) as javax.swing.JComponent
        val dismissButton = buttonsOf(row).first { it.text == "Dismiss" }
        dismissButton.doClick()

        assertEquals("a", dismissedId)
    }

    fun `test a toast without an action renders no action button`() {
        val panel = ToastStackPanel(onAction = {}, onDismiss = {})
        panel.update(listOf(message("a")))

        val row = panel.getComponent(0) as javax.swing.JComponent
        val buttons = buttonsOf(row)

        assertEquals(listOf("Dismiss"), buttons.map { it.text })
    }

    fun `test clicking a toast's action button invokes onAction with its id`() {
        var actedId: String? = null
        val panel = ToastStackPanel(onAction = { id -> actedId = id }, onDismiss = {})
        panel.update(listOf(message("a", action = FeedbackAction("Retry") {})))

        val row = panel.getComponent(0) as javax.swing.JComponent
        val actionButton = buttonsOf(row).first { it.text == "Retry" }
        actionButton.doClick()

        assertEquals("a", actedId)
    }

    fun `test a toast is a rounded card whose corners show the tool window behind it`() {
        val panel = ToastStackPanel(onAction = {}, onDismiss = {})
        panel.update(listOf(FeedbackMessage("e", "Could not open link", FeedbackSeverity.Error, FeedbackAction("Retry") {})))
        val row = panel.getComponent(0) as javax.swing.JComponent
        row.setSize(360, row.preferredSize.height)
        fun layout(container: java.awt.Container) { container.doLayout(); container.components.filterIsInstance<java.awt.Container>().forEach(::layout) }
        layout(row)
        val image = java.awt.image.BufferedImage(row.width, row.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { row.printAll(graphics) } finally { graphics.dispose() }
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"))
        javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of("build", "toast-error.png").toFile())
        assertFalse("Rounded card, no square background", row.isOpaque)
        assertEquals("Top-right corner is outside the radius-5 card", 0, image.getRGB(row.width - 1, 0) ushr 24)
        assertTrue("The card itself is painted", image.getRGB(row.width / 2, row.height / 2) ushr 24 > 0)
        assertTrue(
            "Action and Dismiss are design-system links, not IDE buttons with their own rounded fill",
            buttonsOf(row).all { it is io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton && it.style == io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle.LINK },
        )
    }
}
