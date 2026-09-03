package dev.acme.adbtoolbox.intellij.nav

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.domain.nav.ViewId
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel

/**
 * The neutral navigation region (task 012, `design/README.md` §2 "Rail" — the 34px icon rail).
 * Purely structural/behavioral here: item order, single-selection, and keyboard traversal — no
 * icon, color, dimension, or badge rendering, which is task 043+.
 *
 * Built on [JBList] rather than plain [javax.swing.JButton]s specifically so "arrow-key traversal
 * within navigation" (task 012's scope, `design/designs/ADB Toolbox IA.dc.html`'s "↑ ↓ ← → Moves
 * within a region — rail views") comes from the platform's own standard list key bindings instead
 * of a hand-rolled `KeyListener` — Tab/⇧Tab still moves focus in and out of the whole list to the
 * next/previous region, unaffected by this list's own arrow-key handling.
 *
 * [setSelected] is the programmatic sync path (restoring/reacting to
 * [dev.acme.adbtoolbox.application.nav.NavigationViewModel.state]): it updates the list's selection
 * without re-invoking [onSelect], so a state-driven sync can never feed back into another intent
 * dispatch. [onSelect] fires only for genuine list-selection events (user click or arrow key).
 */
class NavigationRailPanel : JBPanel<NavigationRailPanel>(BorderLayout()) {

    /** Fired when the user changes the selected destination (click or arrow-key). Not fired by [setSelected]. */
    var onSelect: (ViewId) -> Unit = {}

    private val listModel = DefaultListModel<ViewId>().apply { ViewId.entries.forEach(::addElement) }

    val list: JBList<ViewId> = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private var suppressSelectionEvents = false

    init {
        add(list, BorderLayout.CENTER)
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting || suppressSelectionEvents) return@addListSelectionListener
            list.selectedValue?.let { onSelect(it) }
        }
    }

    /** Programmatically selects [viewId] without triggering [onSelect]. Idempotent. */
    fun setSelected(viewId: ViewId) {
        if (list.selectedValue == viewId) return
        suppressSelectionEvents = true
        try {
            list.setSelectedValue(viewId, true)
        } finally {
            suppressSelectionEvents = false
        }
    }
}
