package dev.acme.adbtoolbox.intellij.nav

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
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
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

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
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        layoutOrientation = JBList.VERTICAL
        isOpaque = false
        cellRenderer = RailCellRenderer { badgeFor(it) }
    }

    private var suppressSelectionEvents = false

    init {
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

    private companion object {
        val TOOLTIPS: Map<ViewId, String> = mapOf(
            ViewId.Device to "Device — mirroring, capture, facts",
            ViewId.Apps to "Apps — restart, clear data, uninstall",
            ViewId.Display to "Display — font scale and density",
            ViewId.Network to "Network — global proxy",
            ViewId.Logcat to "Logcat — severity, filters, search",
            ViewId.Settings to "Settings",
        )
    }
}

/**
 * Renders one 26px rail button: [RailGlyphIcon]/[AllIcons.General.Settings], the active/inactive
 * background+border+glyph-color combination, and the [NavigationBadge] dot (`design/README.md`
 * §2: "5px dot at top:2,right:2 ... amber ... red").
 */
private class RailCellRenderer(
    private val badgeFor: (ViewId) -> NavigationBadge,
) : JLabel(), ListCellRenderer<ViewId> {

    private var badge: NavigationBadge = NavigationBadge.None

    init {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: javax.swing.JList<out ViewId>,
        value: ViewId,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val size = AdbToolboxTheme.Sizes.railButton
        preferredSize = java.awt.Dimension(size, size)
        badge = badgeFor(value)

        val glyphColor = if (isSelected) AdbToolboxTheme.Colors.accent else AdbToolboxTheme.Colors.textDim
        icon = if (value == ViewId.Settings) AllIcons.General.Settings else RailGlyphIcon(value, glyphColor)

        background = if (isSelected) AdbToolboxTheme.Colors.accentBg else AdbToolboxTheme.Colors.panel
        border = RailButtonBorder(if (isSelected) AdbToolboxTheme.Colors.accentBorder else null)

        toolTipText = value.name
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (badge == NavigationBadge.None) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = badgeColor(badge)
            val dotSize = 5
            g2.fillOval(width - dotSize - 2, 2, dotSize, dotSize)
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

/** The active rail button's 1px `accentBorder` outline (radius 6); `null` [color] paints nothing (inactive). */
private class RailButtonBorder(private val color: java.awt.Color?) : javax.swing.border.AbstractBorder() {
    override fun getBorderInsets(component: Component): java.awt.Insets = java.awt.Insets(1, 1, 1, 1)

    override fun paintBorder(component: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val paintColor = color ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = paintColor
            val arc = AdbToolboxTheme.Radii.railButton * 2
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}
