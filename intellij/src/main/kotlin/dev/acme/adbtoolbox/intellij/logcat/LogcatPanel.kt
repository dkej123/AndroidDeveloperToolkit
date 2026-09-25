package dev.acme.adbtoolbox.intellij.logcat

import dev.acme.adbtoolbox.intellij.ui.common.ShortcutHints
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.logcat.LogcatControlsState
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.LevelChip
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.AdjustmentListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.OverlayLayout
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Task 048's final visual design for the Logcat view (`design/README.md` §7): the toolbar (search
 * field, pause/autoscroll/wrap icon toggles, a divider, Clear), the V/D/I/W/E level-chip filter row
 * with the package-filter chip, the task 036/[LogcatVirtualList] body with a floating paused pill
 * overlay ([LogcatRowStyle] owns per-row severity/search-hit styling), and the mono footer/empty
 * states. Every rendered value still comes only from [LogcatControlsState] — no ADB/filter/session
 * logic lives here, matching [dev.acme.adbtoolbox.intellij.network.NetworkPanel]'s established
 * shape. The paused pill is stacked over the scroll body with [OverlayLayout] rather than the
 * prototype's CSS `position: absolute`, the same documented simplification
 * [dev.acme.adbtoolbox.intellij.apps.AppsPanel]'s empty-state-over-list layering already uses for
 * "no CSS equivalent" prototype behavior.
 */
class LogcatPanel(
    val virtualList: LogcatVirtualList,
    onQueryChange: (String) -> Unit,
    onSetMinSeverity: (LogSeverity?) -> Unit,
    onTogglePackageFilter: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleWrap: () -> Unit,
    onClearLocal: () -> Unit,
    onJumpToLatest: () -> Unit,
    onResetFilters: () -> Unit,
    private val onManualScrollAway: () -> Unit,
) : JBPanel<LogcatPanel>(BorderLayout()) {

    // ---- toolbar (`design/README.md` §7's toolbar row) ----

    private val searchIconLabel = JBLabel(AdbToolboxIcons.Actions.search)

    private val searchField = JBTextField().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11f))
        emptyText.text = ShortcutHints.withKeyStroke("Search log…", focusSearchKeyStroke())
        toolTipText = ShortcutHints.withKeyStroke("Search log…", focusSearchKeyStroke())
        getAccessibleContext().accessibleName = "Search log"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onQueryChange(text)
            override fun removeUpdate(e: DocumentEvent) = onQueryChange(text)
            override fun changedUpdate(e: DocumentEvent) = onQueryChange(text)
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ESCAPE) return
                if (text.isNotEmpty()) {
                    text = ""
                } else {
                    transferFocus()
                }
            }
        })
    }

    private val clearQueryButton = JButton("×").apply {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        foreground = AdbToolboxTheme.Colors.textFaint
        margin = java.awt.Insets(0, 0, 0, 0)
        isVisible = false
        toolTipText = "Clear search"
        getAccessibleContext().accessibleName = "Clear search"
        addActionListener { searchField.text = "" }
    }

    // `searchWrapStyle`: 24px, radius 4, `field` fill, 1px `borderStrong`, `padding: 0 7px`, gap 6.
    private val searchFieldWrap = RoundedSurface(
        AdbToolboxTheme.Colors.field,
        AdbToolboxTheme.Colors.borderStrong,
        radius = { AdbToolboxTheme.Radii.field },
    ).apply {
        layout = FlexRowLayout(AdbToolboxTheme.Spacing.s3)
        border = JBUI.Borders.empty(0, JBUI.scale(7))
        preferredSize = Dimension(0, AdbToolboxTheme.Sizes.field)
        add(searchIconLabel)
        add(searchField, FlexRowLayout.FILL)
        add(clearQueryButton)
    }

    private val pauseButton = iconToggleButton(AdbToolboxIcons.Actions.pause, "Pause the stream  Space") { onTogglePause() }
    private val followButton = iconToggleButton(AdbToolboxIcons.Actions.autoscroll, "Autoscroll on — following the newest line") {
        onToggleFollow()
    }
    private val wrapButton = iconToggleButton(AdbToolboxIcons.Actions.wrap, "Wrap long lines") { onToggleWrap() }
    private val clearButton = iconButton(TrashGlyphIcon(AdbToolboxTheme.Colors.textDim), "Clear the buffer — does not clear the device log").apply {
        addActionListener { onClearLocal() }
    }

    // `logToolbarBtnsStyle`: gap 2; the divider carries its own `margin: 0 2px`.
    private val toolbarButtons = flexRow(AdbToolboxTheme.Spacing.s1, pauseButton, followButton, wrapButton, DividerLine(), clearButton)

    // `logToolbarStyle`: `padding: 6px 6px 6px 8px`, gap 6, 1px bottom border.
    private val toolbar = flexRow(AdbToolboxTheme.Spacing.s3, searchFieldWrap, toolbarButtons, fill = searchFieldWrap).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.s3),
        )
    }

    // ---- filter row (`design/README.md` §7's level chips + package filter) ----

    private val levelChips: Map<LogSeverity, LevelChip> = listOf(
        LogSeverity.VERBOSE, LogSeverity.DEBUG, LogSeverity.INFO, LogSeverity.WARN, LogSeverity.ERROR,
    ).associateWith { level ->
        LevelChip(level).apply {
            toolTipText = "${level.name.first()} and above"
            addActionListener { onSetMinSeverity(if (level == LogSeverity.VERBOSE) null else level) }
        }
    }
    private val levelChipGroup = ButtonGroup().also { group -> levelChips.values.forEach(group::add) }

    private val packageFilterChip = PackageFilterChip().apply {
        toolTipText = "Limit to the app selected in Apps"
        getAccessibleContext().accessibleName = "Limit to selected app"
        addActionListener { onTogglePackageFilter() }
    }

    // `logFilterRowStyle`: five level chips, spacer, package chip; gap 3, `padding: 0 8px 6px`.
    private val filterRow = run {
        val spacer = flexSpacer()
        flexRow(JBUI.scale(3), *levelChips.values.toTypedArray(), spacer, packageFilterChip, fill = spacer)
    }.apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.s4),
        )
    }

    private val header = JPanel(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.bg
        add(toolbar, BorderLayout.NORTH)
        add(filterRow, BorderLayout.SOUTH)
    }

    // ---- body + floating paused pill (`design/README.md` §7) ----

    private val scrollPane = JScrollPane(virtualList).apply {
        border = BorderFactory.createEmptyBorder()
        viewport.background = AdbToolboxTheme.Colors.bg
    }

    private val jumpToLatestLink = linkButton("Jump to latest") { onJumpToLatest() }
    private val pausedDotLabel = JBLabel(StatusDotIcon(AdbToolboxTheme.Colors.amber, filled = true, diameter = 6))
    private val pausedTextLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.amber
    }
    // `pausedPillStyle`: fully rounded, `panel` fill, 1px amber, `padding: 4px 8px 4px 7px`, gap 7.
    private val pausedPill = RoundedSurface(AdbToolboxTheme.Colors.panel, AdbToolboxTheme.Colors.amber, radius = { AdbToolboxTheme.Radii.pill }).apply {
        layout = FlexRowLayout(JBUI.scale(7))
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s2, JBUI.scale(7), AdbToolboxTheme.Spacing.s2, AdbToolboxTheme.Spacing.s4)
        add(pausedDotLabel)
        add(pausedTextLabel)
        add(jumpToLatestLink)
    }
    private val pausedPillRow = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
        isOpaque = false
        isVisible = false
        add(pausedPill)
    }
    private val pausedPillOverlay = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(AdbToolboxTheme.Spacing.s5)
        add(pausedPillRow, BorderLayout.SOUTH)
    }

    // The overlay is added first: Swing paints children in reverse order, so the first child is on
    // top. Overlapping children also require optimized drawing to be off.
    private val bodyStack = object : JPanel() {
        override fun isOptimizedDrawingEnabled(): Boolean = false
    }.apply {
        layout = OverlayLayout(this)
        add(pausedPillOverlay)
        add(scrollPane)
    }

    // `emptyWrapStyle`: centered column, `padding: 34px 16px 16px`, gap 6.
    private val emptyStateTitleLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.sectionTitle
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val emptyStateBodyLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11f))
        foreground = AdbToolboxTheme.Colors.textDim
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val resetFiltersLink = linkButton("Reset filters") { onResetFilters() }.apply {
        isVisible = false
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val emptyStatePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(34), AdbToolboxTheme.Spacing.s6, AdbToolboxTheme.Spacing.s6, AdbToolboxTheme.Spacing.s6)
        isVisible = false
        add(emptyStateTitleLabel)
        add(javax.swing.Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
        add(emptyStateBodyLabel)
        add(javax.swing.Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
        add(resetFiltersLink)
    }

    private val centerContainer = JPanel(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.bg
        add(emptyStatePanel, BorderLayout.NORTH)
        add(bodyStack, BorderLayout.CENTER)
    }

    // ---- footer (`design/README.md` §7) ----

    private val footerLabel = footerLabel("")
    private val footerBufferLabel = footerLabel("")
    private val footer = run {
        val spacer = flexSpacer()
        flexRow(AdbToolboxTheme.Spacing.s4, footerLabel, spacer, footerBufferLabel, fill = spacer)
    }.apply {
        isOpaque = true
        preferredSize = Dimension(0, AdbToolboxTheme.Sizes.statusBar)
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4),
        )
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        add(header, BorderLayout.NORTH)
        add(centerContainer, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        val bar = scrollPane.verticalScrollBar
        bar.addAdjustmentListener(AdjustmentListener {
            val scrolledAway = followTracker.onAdjusted(bar.value, bar.visibleAmount, bar.maximum)
            if (programmaticScroll || !isShowing) return@AdjustmentListener
            if (scrolledAway) onManualScrollAway()
        })
        // Rows keep arriving while another view is shown; catch up when Logcat becomes visible.
        addHierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && following) {
                SwingUtilities.invokeLater { scrollToBottom() }
            }
        }

        virtualList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "logcat.togglePause")
        virtualList.actionMap.put("logcat.togglePause", actionOf { onTogglePause() })
        virtualList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "logcat.jumpToLatest")
        virtualList.actionMap.put("logcat.jumpToLatest", actionOf { onJumpToLatest() })

        // `design/README.md` Interactions: "⌘F ... focuses Logcat search field". A modifier chord
        // (never bare "F"), so registering it at WHEN_ANCESTOR_OF_FOCUSED_COMPONENT — reachable from
        // anywhere in this view, including the search field itself (re-focus/select-all) — never
        // collides with typing plain text into [searchField]. Uses the platform menu-shortcut mask
        // (⌘ on macOS, Ctrl elsewhere) resolved from `os.name` rather than
        // `Toolkit.getMenuShortcutKeyMaskEx()`, which throws `HeadlessException` in headless test JVMs.
        val focusSearchKeyStroke = focusSearchKeyStroke()
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(focusSearchKeyStroke, "logcat.focusSearch")
        actionMap.put("logcat.focusSearch", actionOf { focusSearchField() })

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyResponsiveColumns(width)
        })
    }

    /** ⌘F on macOS, Ctrl+F elsewhere; the mask comes from `os.name` because
     * `Toolkit.getMenuShortcutKeyMaskEx()` throws `HeadlessException` in headless test JVMs. */
    private fun focusSearchKeyStroke(): KeyStroke {
        val menuShortcutMask = if (System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) {
            java.awt.event.InputEvent.META_DOWN_MASK
        } else {
            java.awt.event.InputEvent.CTRL_DOWN_MASK
        }
        return KeyStroke.getKeyStroke(KeyEvent.VK_F, menuShortcutMask)
    }

    /** `design/README.md` Interactions: "⌘F ... focuses Logcat search field". */
    fun focusSearchField() {
        searchField.requestFocusInWindow()
        searchField.selectAll()
    }

    private var programmaticScroll = false
    private val followTracker = FollowScrollTracker()

    /** Whether the last rendered state follows the newest line (not paused, autoscroll on). */
    private var following = true

    fun scrollToBottom() {
        if (!SwingUtilities.isEventDispatchThread()) return
        programmaticScroll = true
        val bar = scrollPane.verticalScrollBar
        bar.value = bar.maximum
        followTracker.reset(bar.value)
        programmaticScroll = false
    }

    /** `design/README.md`'s wide-breakpoint tag column (`LogcatRowStyle.columnsFor`); also called
     * directly by tests, mirroring [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel.applyResponsiveLayout]. */
    internal fun applyResponsiveColumns(width: Int) {
        val columns = LogcatRowStyle.columnsFor(width)
        if (virtualList.presentation.columns != columns) {
            virtualList.presentation = virtualList.presentation.copy(columns = columns)
        }
    }

    /** Test-only visibility hooks so a test can drive real Swing interactions without a live display. */
    internal val searchFieldForTest: JTextField get() = searchField
    internal val clearQueryButtonForTest: JButton get() = clearQueryButton
    internal val levelButtonsForTest: Map<LogSeverity, JToggleButton> get() = levelChips
    internal val packageFilterChipForTest: JToggleButton get() = packageFilterChip
    internal val pauseButtonForTest: JToggleButton get() = pauseButton
    internal val followButtonForTest: JToggleButton get() = followButton
    internal val wrapButtonForTest: JToggleButton get() = wrapButton
    internal val clearButtonForTest: JButton get() = clearButton
    internal val jumpToLatestLinkForTest: JButton get() = jumpToLatestLink
    internal val pausedPillForTest: JBLabel get() = pausedTextLabel
    internal val resetFiltersLinkForTest: JButton get() = resetFiltersLink
    internal val emptyStateLabelForTest: JBLabel get() = emptyStateTitleLabel
    internal val footerLabelForTest: JBLabel get() = footerLabel
    internal val footerBufferLabelForTest: JBLabel get() = footerBufferLabel

    fun update(state: LogcatControlsState) {
        following = state.follow && state.pauseState !is LogcatPauseState.Paused
        if (searchField.text != state.query) searchField.text = state.query
        clearQueryButton.isVisible = state.query.isNotEmpty()

        levelChips.forEach { (level, chip) -> chip.isSelected = LogcatRowStyle.isChipActive(level, state.minSeverity) }

        packageFilterChip.text = if (state.packageFilterOn) {
            state.packageFilterLabel ?: "all packages"
        } else {
            "all packages"
        }
        if (packageFilterChip.isSelected != state.packageFilterOn) packageFilterChip.isSelected = state.packageFilterOn
        presentPackageFilterChip()

        val paused = state.pauseState is LogcatPauseState.Paused
        if (pauseButton.isSelected != paused) pauseButton.isSelected = paused
        pauseButton.icon = if (paused) AdbToolboxIcons.Actions.resume else AdbToolboxIcons.Actions.pause
        pauseButton.toolTipText = if (paused) "Resume the stream  Space" else "Pause the stream  Space"
        presentIconToggle(pauseButton)

        if (followButton.isSelected != state.follow) followButton.isSelected = state.follow
        followButton.toolTipText = if (state.follow) {
            "Autoscroll on — following the newest line"
        } else {
            "Autoscroll paused — End to resume"
        }
        presentIconToggle(followButton)

        if (wrapButton.isSelected != state.wrap) wrapButton.isSelected = state.wrap
        presentIconToggle(wrapButton)
        virtualList.presentation = virtualList.presentation.copy(wrapLines = state.wrap)

        val showPill = state.hasDevice && (paused || !state.follow)
        pausedPillRow.isVisible = showPill
        pausedTextLabel.text = if (paused) {
            "Paused · ${state.unseen.unseenCount} new lines"
        } else {
            "${state.unseen.unseenCount} new lines below"
        }

        footerLabel.text = "${state.visibleCount} of ${state.totalRetainedCount} lines"
        footerBufferLabel.text = LogcatRowStyle.formatBufferSize(state.totalBytes)

        val showEmpty = !state.hasDevice || (state.totalRetainedCount == 0) || state.isFilteredEmpty
        emptyStatePanel.isVisible = showEmpty
        resetFiltersLink.isVisible = state.isFilteredEmpty
        when {
            !state.hasDevice -> {
                emptyStateTitleLabel.text = "No device connected"
                emptyStateBodyLabel.text = "Logcat attaches automatically when a device comes online."
            }
            state.isFilteredEmpty -> {
                emptyStateTitleLabel.text = "Nothing matches these filters"
                emptyStateBodyLabel.text = "${state.filterSummary.orEmpty()}."
            }
            state.cleared -> {
                emptyStateTitleLabel.text = "Buffer cleared"
                emptyStateBodyLabel.text = "New lines will appear as the device emits them."
            }
            state.totalRetainedCount == 0 -> {
                emptyStateTitleLabel.text = "No lines yet"
                emptyStateBodyLabel.text = ""
            }
            else -> {
                emptyStateTitleLabel.text = ""
                emptyStateBodyLabel.text = ""
            }
        }
    }

    private fun presentIconToggle(button: JToggleButton) {
        if (button.isSelected) {
            button.isOpaque = true
            button.background = AdbToolboxTheme.Colors.accentBg
            button.border = SolidChipBorder(AdbToolboxTheme.Colors.accentBorder)
        } else {
            button.isOpaque = false
            button.border = BorderFactory.createEmptyBorder()
        }
    }

    private fun presentPackageFilterChip() {
        packageFilterChip.foreground = if (packageFilterChip.isSelected) AdbToolboxTheme.Colors.accent else AdbToolboxTheme.Colors.textFaint
        packageFilterChip.revalidate()
        packageFilterChip.repaint()
    }

    /** Test/disposal seam: no owned listeners beyond Swing's own, kept for [dev.acme.adbtoolbox.intellij.network.NetworkPanel]-style symmetry. */
    fun disposePanel() = Unit

    private companion object {
        fun iconToggleButton(icon: Icon, tooltip: String, onToggle: () -> Unit): JToggleButton = JToggleButton(icon).apply {
            preferredSize = Dimension(AdbToolboxTheme.Sizes.iconButton, AdbToolboxTheme.Sizes.iconButton)
            toolTipText = tooltip
            getAccessibleContext().accessibleName = tooltip.substringBefore("  ")
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            addActionListener { onToggle() }
        }

        fun iconButton(icon: Icon, tooltip: String): JButton = JButton(icon).apply {
            preferredSize = Dimension(AdbToolboxTheme.Sizes.iconButton, AdbToolboxTheme.Sizes.iconButton)
            toolTipText = tooltip
            getAccessibleContext().accessibleName = tooltip.substringBefore("  ")
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
        }

        fun linkButton(text: String, onClick: () -> Unit) = DesignButton(text, DesignButtonStyle.LINK).apply {
            addActionListener { onClick() }
        }

        fun footerLabel(text: String) = JBLabel(text).apply {
            font = AdbToolboxTheme.Typography.monoMeta
            foreground = AdbToolboxTheme.Colors.textFaint
        }
    }
}

private fun actionOf(action: () -> Unit): javax.swing.Action = object : javax.swing.AbstractAction() {
    override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
}

/** The toolbar's 1px×14px divider between the pause/autoscroll/wrap toggles and Clear
 * (`design/README.md` §7's toolbar; no delivered SVG for a bare rule, same "redraw with plain
 * primitives" simplification [dev.acme.adbtoolbox.intellij.apps.AppsPanel]'s `EyeGlyphIcon` uses). */
private class DividerLine : JComponent() {
    init {
        preferredSize = Dimension(JBUI.scale(1), JBUI.scale(14))
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        g.color = AdbToolboxTheme.Colors.border
        g.fillRect(0, (height - JBUI.scale(14)) / 2, width, JBUI.scale(14))
    }
}

/** The Clear button's trash glyph — there is no delivered SVG for it (unlike the toolbar's
 * pause/autoscroll/wrap icons), so it is redrawn with plain [Graphics2D] primitives the same way
 * [dev.acme.adbtoolbox.intellij.apps.AppsPanel]'s `EyeGlyphIcon` redraws its own no-asset glyph. */
private class TrashGlyphIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(9)
    override fun getIconHeight(): Int = JBUI.scale(10)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color

            val lidStroke = JBUI.scale(2.4f)
            g2.stroke = BasicStroke(lidStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val lidY = y + lidStroke / 2f
            g2.draw(java.awt.geom.Line2D.Float(x.toFloat(), lidY, (x + iconWidth).toFloat(), lidY))

            val bodyStroke = JBUI.scale(1.4f)
            g2.stroke = BasicStroke(bodyStroke)
            val bodyTop = y + lidStroke + JBUI.scale(1f)
            val arc = JBUI.scale(2f)
            g2.draw(
                RoundRectangle2D.Float(
                    x + bodyStroke / 2f,
                    bodyTop,
                    iconWidth - bodyStroke,
                    (y + iconHeight) - bodyTop - bodyStroke / 2f,
                    arc,
                    arc,
                ),
            )
        } finally {
            g2.dispose()
        }
    }
}

/**
 * `pkgFilterStyle`: 20px, radius 4, `padding: 0 7px`, mono 10px, at most 160px wide with an
 * ellipsis. On = accent text on `accentBg` + 1px `accentBorder`; off = `textFaint` + 1px `border`.
 */
private class PackageFilterChip : JToggleButton() {
    init {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        border = JBUI.Borders.empty(0, JBUI.scale(7))
        margin = java.awt.Insets(0, 0, 0, 0)
    }

    override fun getPreferredSize(): Dimension {
        val insets = insets
        val textWidth = getFontMetrics(font).stringWidth(text.orEmpty()) + insets.left + insets.right
        return Dimension(minOf(textWidth, JBUI.scale(160)), JBUI.scale(20))
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = (AdbToolboxTheme.Radii.field * 2).toFloat()
            if (isSelected) {
                g2.color = AdbToolboxTheme.Colors.accentBg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            }
            g2.color = if (isSelected) AdbToolboxTheme.Colors.accentBorder else AdbToolboxTheme.Colors.border
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
