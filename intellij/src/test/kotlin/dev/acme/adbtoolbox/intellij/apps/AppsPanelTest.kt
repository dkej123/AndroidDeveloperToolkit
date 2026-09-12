package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppsRow
import dev.acme.adbtoolbox.application.apps.AppsViewState

/**
 * [AppsPanel] is the toolbar (search + "show system packages" toggle) plus [AppsVirtualList] and an
 * empty-state message (`design/README.md` §4) — purely structural, like
 * [dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarPanel]; final visual design is a later task.
 */
class AppsPanelTest : BasePlatformTestCase() {

    private fun row(name: String, selected: Boolean = false) =
        AppsRow(packageName = name, label = name, labelResolved = false, isDebuggable = null, isSelected = selected)

    fun `test typing in the search field invokes onQueryChange with the exact text`() {
        var query: String? = null
        val panel = AppsPanel(onQueryChange = { query = it }, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.searchFieldForTest.text = "shop"
        panel.searchFieldForTest.document.let { doc ->
            // Force the document listener path a real keystroke would take.
            doc.insertString(doc.length, "!", null)
            doc.remove(doc.length - 1, 1)
        }

        assertEquals("shop", query)
    }

    fun `test clicking the system-packages toggle invokes onToggleSystemPackages`() {
        var toggled = 0
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = { toggled++ }, onSelect = {}, onClearFilter = {})

        panel.systemToggleForTest.doClick()

        assertEquals(1, toggled)
    }

    fun `test update renders rows and reflects the search query and toggle state`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(
            AppsViewState(
                query = "sh",
                showSystemPackages = true,
                hasDevice = true,
                isLoading = false,
                rows = listOf(row("com.acme.shop")),
            ),
        )

        assertEquals("sh", panel.searchFieldForTest.text)
        assertTrue(panel.systemToggleForTest.isSelected)
        assertEquals(1, panel.listForTest.model.size)
    }

    fun `test a filtered-empty state shows the empty message and a working clear-filter action`() {
        var cleared = false
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = { cleared = true })

        panel.update(AppsViewState(query = "nomatch", hasDevice = true, isLoading = false, rows = emptyList()))

        assertTrue(panel.emptyStateTextForTest.isNotBlank())
        panel.clearFilterLinkForTest.doClick()
        assertTrue(cleared)
    }

    fun `test with no device the panel shows no rows and no crash`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})

        panel.update(AppsViewState(hasDevice = false, isLoading = false, rows = emptyList()))

        assertEquals(0, panel.listForTest.model.size)
    }

    fun `test disposing the panel tears down its list without throwing`() {
        val panel = AppsPanel(onQueryChange = {}, onToggleSystemPackages = {}, onSelect = {}, onClearFilter = {})
        panel.update(AppsViewState(hasDevice = true, isLoading = false, rows = listOf(row("com.acme.shop"))))

        panel.disposePanel()
    }
}
