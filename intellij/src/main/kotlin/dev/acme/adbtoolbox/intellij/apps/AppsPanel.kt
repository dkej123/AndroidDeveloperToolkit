package dev.acme.adbtoolbox.intellij.apps

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsViewState
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Task 022's Apps view toolbar + virtualized list + empty-state message, plus task 023's minimal
 * Force-stop/Launch/Restart action-footer buttons (`design/README.md` §4) — purely structural,
 * mirroring [dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarPanel]: no color, icon,
 * spacing, or final copy/layout is decided here. [onQueryChange] fires on every keystroke (the
 * reducer itself decides what, if anything, to filter — an empty/blank query matches everything,
 * per [dev.acme.adbtoolbox.application.apps.AppsViewModel]), [onToggleSystemPackages] is the "Show
 * system packages" control, [onSelect] carries the exact clicked row's package name,
 * [onClearFilter] backs both the toolbar's "×" and the empty-state's "Clear filter" link, and
 * [onForceStop]/[onLaunch]/[onRestart] back task 023's action-footer buttons — scoped to whichever
 * package [update]/[updateLifecycle] most recently reflected as selected, never constructing a
 * command themselves (all ADB logic lives in
 * [dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase]).
 */
class AppsPanel(
    onQueryChange: (String) -> Unit,
    onToggleSystemPackages: () -> Unit,
    onSelect: (String) -> Unit,
    onClearFilter: () -> Unit,
    onForceStop: () -> Unit = {},
    onLaunch: () -> Unit = {},
    onRestart: () -> Unit = {},
) : JBPanel<AppsPanel>(BorderLayout()) {

    private val searchField = JBTextField().apply {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onQueryChange(text)
            override fun removeUpdate(e: DocumentEvent) = onQueryChange(text)
            override fun changedUpdate(e: DocumentEvent) = onQueryChange(text)
        })
    }
    private val systemToggle = JToggleButton("Show system packages").apply {
        addActionListener { onToggleSystemPackages() }
    }
    private val toolbar = JPanel(BorderLayout()).apply {
        add(searchField, BorderLayout.CENTER)
        add(systemToggle, BorderLayout.EAST)
    }

    private val list = AppsVirtualList(onSelect = onSelect)
    private val scrollPane = JScrollPane(list)

    private val emptyStateLabel = JBLabel("")
    private val clearFilterLink = JButton("Clear filter").apply {
        addActionListener { onClearFilter() }
    }
    private val emptyStatePanel = JPanel(BorderLayout()).apply {
        add(emptyStateLabel, BorderLayout.CENTER)
        add(clearFilterLink, BorderLayout.SOUTH)
        isVisible = false
    }

    // Task 023's minimal action footer (`design/README.md` §4: primary Restart, secondary
    // Force-stop, secondary Launch) — enablement/disablement is driven entirely by
    // [updateLifecycle]; no button here ever issues an ADB call itself.
    private val restartButton = JButton("Restart").apply {
        toolTipText = "Force-stop, then launch the main activity"
        addActionListener { onRestart() }
        isEnabled = false
    }
    private val forceStopButton = JButton("Force-stop").apply {
        toolTipText = "am force-stop — leaves data intact"
        addActionListener { onForceStop() }
        isEnabled = false
    }
    private val launchButton = JButton("Launch").apply {
        toolTipText = "monkey launch of the main activity"
        addActionListener { onLaunch() }
        isEnabled = false
    }
    private val actionFooterPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(restartButton)
        add(forceStopButton)
        add(launchButton)
    }

    private val southContainer = JPanel(BorderLayout()).apply {
        add(emptyStatePanel, BorderLayout.NORTH)
        add(actionFooterPanel, BorderLayout.SOUTH)
    }

    init {
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(southContainer, BorderLayout.SOUTH)
    }

    /** Test-only visibility hooks so a test can drive real Swing interactions without a live display. */
    internal val searchFieldForTest: JTextField get() = searchField
    internal val systemToggleForTest: JToggleButton get() = systemToggle
    internal val listForTest: AppsVirtualList get() = list
    internal val emptyStateTextForTest: String get() = emptyStateLabel.text
    internal val clearFilterLinkForTest: JButton get() = clearFilterLink
    internal val restartButtonForTest: JButton get() = restartButton
    internal val forceStopButtonForTest: JButton get() = forceStopButton
    internal val launchButtonForTest: JButton get() = launchButton

    fun update(state: AppsViewState) {
        if (searchField.text != state.query) searchField.text = state.query
        if (systemToggle.isSelected != state.showSystemPackages) systemToggle.isSelected = state.showSystemPackages

        list.virtualModel.apply(state.rows)
        list.syncSelectionFromRows()

        emptyStatePanel.isVisible = state.isFilteredEmpty || state.isGenuinelyEmpty
        emptyStateLabel.text = when {
            state.errorMessage != null -> state.errorMessage
            state.isFilteredEmpty -> "No packages match “${state.query}”"
            state.isGenuinelyEmpty -> "No packages found"
            else -> ""
        }
        clearFilterLink.isVisible = state.isFilteredEmpty
    }

    /**
     * Reflects task 023's [AppLifecycleViewState] onto the action-footer buttons: every button is
     * enabled only when [AppLifecycleViewState.actionsEnabled] is `true` — a device is eligible, a
     * package is selected, and no action is already running for it — never independently decided
     * per button.
     */
    fun updateLifecycle(state: AppLifecycleViewState) {
        val enabled = state.actionsEnabled
        restartButton.isEnabled = enabled
        forceStopButton.isEnabled = enabled
        launchButton.isEnabled = enabled
    }

    /** Test/disposal seam: releases [list]'s own listeners. Coordinators call this from their own `dispose()`. */
    fun disposePanel() {
        list.disposeList()
    }
}
