package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.feedback.FeedbackAction
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import javax.swing.JButton

/** [ToastStackPanel] renders task 013's bounded toast list. Kept as a [BasePlatformTestCase] like every other test in this module. */
class ToastStackPanelTest : BasePlatformTestCase() {

    private fun message(id: String, action: FeedbackAction? = null) =
        FeedbackMessage(id = id, text = "text-$id", severity = FeedbackSeverity.Info, action = action)

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
        val dismissButton = row.components.filterIsInstance<JButton>().first { it.text == "Dismiss" }
        dismissButton.doClick()

        assertEquals("a", dismissedId)
    }

    fun `test a toast without an action renders no action button`() {
        val panel = ToastStackPanel(onAction = {}, onDismiss = {})
        panel.update(listOf(message("a")))

        val row = panel.getComponent(0) as javax.swing.JComponent
        val buttons = row.components.filterIsInstance<JButton>()

        assertEquals(listOf("Dismiss"), buttons.map { it.text })
    }

    fun `test clicking a toast's action button invokes onAction with its id`() {
        var actedId: String? = null
        val panel = ToastStackPanel(onAction = { id -> actedId = id }, onDismiss = {})
        panel.update(listOf(message("a", action = FeedbackAction("Retry") {})))

        val row = panel.getComponent(0) as javax.swing.JComponent
        val actionButton = row.components.filterIsInstance<JButton>().first { it.text == "Retry" }
        actionButton.doClick()

        assertEquals("a", actedId)
    }
}
