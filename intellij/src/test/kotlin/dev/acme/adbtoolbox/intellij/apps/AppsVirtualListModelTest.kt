package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppsRow
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private fun row(name: String, selected: Boolean = false, debuggable: Boolean? = null) =
    AppsRow(packageName = name, label = name, labelResolved = false, isDebuggable = debuggable, isSelected = selected)

/**
 * [AppsVirtualListModel] backs [AppsVirtualList] (task 022): a `javax.swing.JList`-native
 * virtualized model — only the rows a viewport actually asks for via [getElementAt] are ever
 * rendered, so this class only needs to hold/replace the full (already filtered) row snapshot
 * [AppsViewModel][dev.acme.adbtoolbox.application.apps.AppsViewModel] computes, unlike task 036's
 * Logcat model which additionally has to coalesce/evict a 10k+-row append stream.
 */
class AppsVirtualListModelTest : BasePlatformTestCase() {

    fun `test a fresh model has no rows`() {
        val model = AppsVirtualListModel { true }

        assertEquals(0, model.size)
    }

    fun `test apply replaces the full row snapshot in the given order`() {
        val model = AppsVirtualListModel { true }

        model.apply(listOf(row("com.acme.shop"), row("com.acme.wallet")))

        assertEquals(2, model.size)
        assertEquals("com.acme.shop", model.getElementAt(0).packageName)
        assertEquals("com.acme.wallet", model.getElementAt(1).packageName)
    }

    fun `test a second apply fully replaces the previous snapshot`() {
        val model = AppsVirtualListModel { true }
        model.apply(listOf(row("com.acme.shop"), row("com.acme.wallet")))

        model.apply(listOf(row("com.acme.other")))

        assertEquals(1, model.size)
        assertEquals("com.acme.other", model.getElementAt(0).packageName)
    }

    fun `test applying an empty list clears all rows`() {
        val model = AppsVirtualListModel { true }
        model.apply(listOf(row("com.acme.shop")))

        model.apply(emptyList())

        assertEquals(0, model.size)
    }

    fun `test apply fires list data events so a bound JList repaints`() {
        val model = AppsVirtualListModel { true }
        var added = 0
        var removed = 0
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) {
                added++
            }

            override fun intervalRemoved(e: ListDataEvent) {
                removed++
            }

            override fun contentsChanged(e: ListDataEvent) = Unit
        })

        model.apply(listOf(row("com.acme.shop")))
        model.apply(listOf(row("com.acme.other")))

        assertTrue(added > 0)
        assertTrue(removed > 0)
    }

    fun `test mutation off the EDT is rejected`() {
        val model = AppsVirtualListModel { false }

        val failure = runCatching { model.apply(listOf(row("com.acme.shop"))) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("EDT"))
    }

    fun `test a large synthetic snapshot remains ordered and addressable without eagerly rendering every row`() {
        val model = AppsVirtualListModel { true }
        val rows = (1..5_000).map { row("com.acme.app$it") }

        model.apply(rows)

        assertEquals(5_000, model.size)
        assertEquals("com.acme.app1", model.getElementAt(0).packageName)
        assertEquals("com.acme.app5000", model.getElementAt(4_999).packageName)
    }
}
