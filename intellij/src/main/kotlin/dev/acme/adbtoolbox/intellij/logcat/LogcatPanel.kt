package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import dev.acme.adbtoolbox.application.logcat.LogcatControlsState
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.AdjustmentListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Task 037's minimal Logcat view (`design/README.md` §7): search field, level chips, package-filter
 * chip, pause/autoscroll/wrap/clear toolbar buttons, the task 036 virtualized body, a paused pill,
 * a footer, and empty-state text — purely structural, mirroring [dev.acme.adbtoolbox.intellij.network.NetworkPanel]:
 * no color, icon, spacing, or final copy/layout is decided here (task 048's). [onSpace]/[onEnd] back
 * `design/README.md`'s Space/End shortcuts, scoped to [virtualList] itself (via
 * [javax.swing.JComponent.WHEN_FOCUSED]/ancestor input maps) so they never hijack Space/End while
 * the search field has focus — the "input-safe" requirement task 037 calls for.
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

    private val searchField = JBTextField().apply {
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

    private val levelButtons: Map<LogSeverity?, JButton> = (listOf(null) + LogSeverity.entries).associateWith { level ->
        JButton(level?.name?.first()?.toString() ?: "V+").apply {
            addActionListener { onSetMinSeverity(level) }
        }
    }

    private val packageFilterChip = JButton().apply { addActionListener { onTogglePackageFilter() } }
    private val pauseButton = JButton().apply { addActionListener { onTogglePause() } }
    private val followButton = JButton().apply { addActionListener { onToggleFollow() } }
    private val wrapButton = JButton().apply { addActionListener { onToggleWrap() } }
    private val clearButton = JButton("Clear").apply { addActionListener { onClearLocal() } }

    private val jumpToLatestLink = JButton("Jump to latest").apply { addActionListener { onJumpToLatest() } }
    private val pausedPill = JBLabel("").apply { isVisible = false }
    private val resetFiltersLink = JButton("Reset filters").apply { addActionListener { onResetFilters() } }
    private val emptyStateLabel = JBLabel("").apply { isVisible = false }
    private val footerLabel = JBLabel("")

    private val scrollPane = JScrollPane(virtualList)
    private var programmaticScroll = false

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(searchField)
            add(pauseButton)
            add(followButton)
            add(wrapButton)
            add(clearButton)
        }
        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            levelButtons.values.forEach(::add)
            add(packageFilterChip)
        }
        val pillRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(pausedPill)
            add(jumpToLatestLink)
        }
        val header = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(filterRow, BorderLayout.CENTER)
            add(pillRow, BorderLayout.SOUTH)
        }
        val emptyRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(emptyStateLabel)
            add(resetFiltersLink)
        }
        val footer = JPanel(BorderLayout()).apply {
            add(emptyRow, BorderLayout.NORTH)
            add(footerLabel, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        val bar = scrollPane.verticalScrollBar
        bar.addAdjustmentListener(AdjustmentListener {
            if (programmaticScroll) return@AdjustmentListener
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum
            if (!atBottom) onManualScrollAway()
        })

        virtualList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "logcat.togglePause")
        virtualList.actionMap.put("logcat.togglePause", actionOf { onTogglePause() })
        virtualList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "logcat.jumpToLatest")
        virtualList.actionMap.put("logcat.jumpToLatest", actionOf { onJumpToLatest() })
    }

    fun scrollToBottom() {
        if (!SwingUtilities.isEventDispatchThread()) return
        programmaticScroll = true
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        programmaticScroll = false
    }

    /** Test-only visibility hooks so a test can drive real Swing interactions without a live display. */
    internal val searchFieldForTest: JTextField get() = searchField
    internal val levelButtonsForTest: Map<LogSeverity?, JButton> get() = levelButtons
    internal val packageFilterChipForTest: JButton get() = packageFilterChip
    internal val pauseButtonForTest: JButton get() = pauseButton
    internal val followButtonForTest: JButton get() = followButton
    internal val wrapButtonForTest: JButton get() = wrapButton
    internal val clearButtonForTest: JButton get() = clearButton
    internal val jumpToLatestLinkForTest: JButton get() = jumpToLatestLink
    internal val pausedPillForTest: JBLabel get() = pausedPill
    internal val resetFiltersLinkForTest: JButton get() = resetFiltersLink
    internal val emptyStateLabelForTest: JBLabel get() = emptyStateLabel
    internal val footerLabelForTest: JBLabel get() = footerLabel

    fun update(state: LogcatControlsState) {
        if (searchField.text != state.query) searchField.text = state.query

        levelButtons.forEach { (level, button) -> button.isEnabled = state.minSeverity != level }

        packageFilterChip.text = if (state.packageFilterOn) {
            state.packageFilterLabel ?: "all packages"
        } else {
            "all packages"
        }
        packageFilterChip.isSelected = state.packageFilterOn

        val paused = state.pauseState is LogcatPauseState.Paused
        pauseButton.text = if (paused) "Resume" else "Pause"
        followButton.text = if (state.follow) "Autoscroll on" else "Autoscroll off"
        wrapButton.text = if (state.wrap) "Wrap on" else "Wrap off"

        val showPill = paused || !state.follow
        pausedPill.isVisible = showPill
        jumpToLatestLink.isVisible = showPill
        pausedPill.text = if (paused) {
            "Paused · ${state.unseen.unseenCount} new lines"
        } else {
            "${state.unseen.unseenCount} new lines below"
        }

        footerLabel.text = "${state.visibleCount} of ${state.totalRetainedCount} lines"

        val showEmpty = !state.hasDevice || (state.totalRetainedCount == 0) || state.isFilteredEmpty
        emptyStateLabel.isVisible = showEmpty
        resetFiltersLink.isVisible = state.isFilteredEmpty
        emptyStateLabel.text = when {
            !state.hasDevice -> "No device connected"
            state.isFilteredEmpty -> "Nothing matches these filters — ${state.filterSummary.orEmpty()}"
            state.cleared -> "Buffer cleared"
            state.totalRetainedCount == 0 -> "No lines yet"
            else -> ""
        }
    }

    /** Test/disposal seam: no owned listeners beyond Swing's own, kept for [dev.acme.adbtoolbox.intellij.network.NetworkPanel]-style symmetry. */
    fun disposePanel() = Unit
}

private fun actionOf(action: () -> Unit): javax.swing.Action = object : javax.swing.AbstractAction() {
    override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
}
