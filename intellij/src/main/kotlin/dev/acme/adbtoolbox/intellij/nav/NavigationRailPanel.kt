package dev.acme.adbtoolbox.intellij.nav

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.RailGlyphIcon
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * The neutral navigation region (task 012, `design/README.md` §2 "Rail" — the 34px icon rail),
 * with task 043's supplied visual treatment applied: 26px square buttons (radius 6), the active
 * destination's `accentBg` fill/`accentBorder` outline/accent glyph, an inactive `textDim` glyph
 * with hover-only fill, per-destination tooltips, and the amber/red 5px override/attention badge
 * dot reserved by [dev.acme.adbtoolbox.domain.nav.NavigationBadges] (task 012) and populated by
 * [updateBadges].
 *
 * Built on [JBList] rather than plain [javax.swing.JButton]s specifically so "arrow-key traversal
 * within navigation" (task 012's scope, `design/designs/ADB Toolbox IA.dc.html`'s "↑ ↓ ← → Moves
 * within a region — rail views") comes from the platform's own standard list key bindings instead
 * of a hand-rolled `KeyListener` — Tab/⇧Tab still moves focus in and out of the whole list to the
 * next/previous region, unaffected by this list's own arrow-key handling.
 *
 * `design/README.md` pins Settings to the rail bottom via a flex spacer, visually separated from
 * the five feature destinations. This class keeps [ViewId.Settings] as the list's sixth,
 * last-in-order entry rather than splitting it into a second component: task 012's single-list
 * keyboard/selection model (and the coordinator/test surface built on `list.selectedIndex`) is
 * preserved as-is; only the Settings row's glyph/tooltip differ. Documented native/structural
 * deviation from the prototype's two-region layout.
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

    private var badges: Map<ViewId, NavigationBadge> = emptyMap()

    val list: JBList<ViewId> = object : JBList<ViewId>(listModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index !in 0 until model.size) return null
            return TOOLTIPS[model.getElementAt(index)]
        }

        // The Settings row is stretched to pin its button to the rail bottom; only the painted
        // button area at the bottom of that row is a hit target, never the spacer above it.
        override fun locationToIndex(location: java.awt.Point): Int {
            val index = super.locationToIndex(location)
            if (index !in 0 until model.size || model.getElementAt(index) != ViewId.Settings) return index
            val bounds = getCellBounds(index, index) ?: return index
            return if (location.y >= bounds.y + bounds.height - AdbToolboxTheme.Sizes.railButton) index else -1
        }

        // Cell heights depend on the rail height (the stretched Settings row), so a height change
        // must invalidate the list UI's cached cell geometry.
        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            val heightChanged = height != this.height
            super.setBounds(x, y, width, height)
            if (heightChanged) {
                fixedCellHeight = 1
                fixedCellHeight = -1
            }
        }
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        layoutOrientation = JBList.VERTICAL
        isOpaque = false
        // `railStyle`: `padding: 4px 0 6px`.
        border = javax.swing.BorderFactory.createEmptyBorder(
            AdbToolboxTheme.Spacing.s2, 0, AdbToolboxTheme.Spacing.s3, 0,
        )
        cellRenderer = RailCellRenderer({ badgeFor(it) }, { settingsRowHeight() })
    }

    private var suppressSelectionEvents = false

    init {
        preferredSize = java.awt.Dimension(AdbToolboxTheme.Sizes.rail, 0)
        minimumSize = java.awt.Dimension(AdbToolboxTheme.Sizes.rail, 0)
        isOpaque = true
        background = AdbToolboxTheme.Colors.panel
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

    /** Publishes the current badge map (`design/README.md` §2's amber/red rail dots) — read by [RailCellRenderer]. */
    fun updateBadges(badges: Map<ViewId, NavigationBadge>) {
        this.badges = badges
        list.repaint()
    }

    private fun badgeFor(viewId: ViewId): NavigationBadge = badges[viewId] ?: NavigationBadge.None

    /** The flex spacer above Settings (`railStyle` + `spacerStyle`): whatever height the five
     * destination rows leave, never less than one regular row. */
    private fun settingsRowHeight(): Int {
        val row = RAIL_ROW_HEIGHT()
        val destinations = listModel.size() - 1
        val available = list.height - list.insets.top - list.insets.bottom - destinations * row
        return available.coerceAtLeast(row)
    }
}

/** A 26px rail button plus the rail's 2px gap. */
private val RAIL_ROW_HEIGHT: () -> Int = { AdbToolboxTheme.Sizes.railButton + AdbToolboxTheme.Spacing.s1 }

/** Shared by [NavigationRailPanel]'s mouse-hover tooltip and [RailCellRenderer]'s per-row accessible name. */
private val TOOLTIPS: Map<ViewId, String> = mapOf(
    ViewId.Device to "Device — mirroring, capture, facts",
    ViewId.Apps to "Apps — restart, clear data, uninstall",
    ViewId.Display to "Display — font scale and density",
    ViewId.Network to "Network — global proxy",
    ViewId.Logcat to "Logcat — severity, filters, search",
    ViewId.Settings to "Settings",
)

/**
 * Renders one 26px rail button: [RailGlyphIcon]/[AllIcons.General.Settings], the active/inactive
 * background+border+glyph-color combination, and the [NavigationBadge] dot (`design/README.md`
 * §2: "5px dot at top:2,right:2 ... amber ... red").
 */
private class RailCellRenderer(
    private val badgeFor: (ViewId) -> NavigationBadge,
    private val settingsRowHeight: () -> Int,
) : JLabel(), ListCellRenderer<ViewId> {

    private var badge: NavigationBadge = NavigationBadge.None
    private var selected = false
    private var pinnedToBottom = false

    init {
        isOpaque = false
    }

    override fun getListCellRendererComponent(
        list: javax.swing.JList<out ViewId>,
        value: ViewId,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        pinnedToBottom = value == ViewId.Settings
        val height = if (pinnedToBottom) settingsRowHeight() else RAIL_ROW_HEIGHT()
        preferredSize = java.awt.Dimension(AdbToolboxTheme.Sizes.rail, height)
        badge = badgeFor(value)
        selected = isSelected
        background = if (isSelected) AdbToolboxTheme.Colors.accentBg else AdbToolboxTheme.Colors.panel

        val glyphColor = if (isSelected) AdbToolboxTheme.Colors.accent else AdbToolboxTheme.Colors.textDim
        icon = if (value == ViewId.Settings) AllIcons.General.Settings else RailGlyphIcon(value, glyphColor)

        toolTipText = TOOLTIPS[value] ?: value.name
        getAccessibleContext().accessibleName = TOOLTIPS[value] ?: value.name
        return this
    }

    /** `railItems[].btnStyle`: a 26px square (radius 6) centered in the 34px rail; active =
     * `accentBg` fill + 1px `accentBorder`; badge = 5px dot at `top: 2, right: 2` of the button. */
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = AdbToolboxTheme.Sizes.railButton
            val x = (width - size) / 2
            val y = if (pinnedToBottom) height - size else 0
            val arc = AdbToolboxTheme.Radii.railButton * 2
            if (selected) {
                g2.color = background
                g2.fillRoundRect(x, y, size, size, arc, arc)
                g2.color = AdbToolboxTheme.Colors.accentBorder
                g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
            }
            icon?.let { glyph ->
                glyph.paintIcon(this, g2, x + (size - glyph.iconWidth) / 2, y + (size - glyph.iconHeight) / 2)
            }
            if (badge != NavigationBadge.None) {
                g2.color = badgeColor(badge)
                val dotSize = JBUI.scale(5)
                val inset = JBUI.scale(2)
                g2.fillOval(x + size - dotSize - inset, y + inset, dotSize, dotSize)
            }
        } finally {
            g2.dispose()
        }
    }

    /**
     * `design/README.md` §2 gives Logcat's badge the red color and Display/Network's the amber
     * color, but [NavigationBadge] carries no color of its own (task 012's scope: "no business
     * rules"). [badgeColor] is a rendering-only convention, not a fabricated business rule:
     * [NavigationBadge.Attention] is the "errors are arriving" shape the supplied design paints
     * red (Logcat), while [NavigationBadge.Count] is the "a non-default value is applied" shape
     * it paints amber (Display/Network).
     */
    private fun badgeColor(badge: NavigationBadge) = when (badge) {
        is NavigationBadge.Attention -> AdbToolboxTheme.Colors.red
        else -> AdbToolboxTheme.Colors.amber
    }
}
