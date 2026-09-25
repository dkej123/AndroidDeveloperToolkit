package dev.acme.adbtoolbox.intellij.apps

import dev.acme.adbtoolbox.intellij.ui.common.ShortcutHints
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewState
import dev.acme.adbtoolbox.application.apps.AppsViewState
import dev.acme.adbtoolbox.application.apps.ClearDataViewState
import dev.acme.adbtoolbox.application.apps.UninstallViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.border.AbstractBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Task 045's final visual design for the Apps view (`design/README.md` §4): the toolbar's search
 * field (leading glyph, "×" clear, "Show system packages" icon toggle), the virtualized list (see
 * [AppsVirtualList]/[AppsRowRenderer] for row chrome), the two-line filtered/genuinely-empty
 * states, the pinned action footer's selected-app row + primary/secondary lifecycle buttons, and
 * the dashed-border "Destructive" group with the red-outlined Clear data/Uninstall buttons. No ADB
 * or command logic lives here — every callback still only forwards to whichever view model already
 * owned it (task 022's [onQueryChange]/[onToggleSystemPackages]/[onSelect]/[onClearFilter], task
 * 023's [onForceStop]/[onLaunch]/[onRestart], and tasks 024/025's [onClearData]/[onUninstall]).
 */
class AppsPanel(
    onQueryChange: (String) -> Unit,
    onToggleSystemPackages: () -> Unit,
    onSelect: (String) -> Unit,
    onClearFilter: () -> Unit,
    onForceStop: () -> Unit = {},
    onLaunch: () -> Unit = {},
    onRestart: () -> Unit = {},
    onClearData: () -> Unit = {},
    onUninstall: () -> Unit = {},
) : JBPanel<AppsPanel>(BorderLayout()) {

    private val searchIconLabel = JBLabel(AdbToolboxIcons.Actions.search)

    private val searchField = JBTextField().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11f))
        emptyText.text = "Filter packages…"
        getAccessibleContext().accessibleName = "Filter packages"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onQueryChange(text)
            override fun removeUpdate(e: DocumentEvent) = onQueryChange(text)
            override fun changedUpdate(e: DocumentEvent) = onQueryChange(text)
        })
    }

    private val clearQueryButton = JButton("×").apply {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        foreground = AdbToolboxTheme.Colors.textFaint
        margin = java.awt.Insets(0, 0, 0, 0)
        isVisible = false
        addActionListener { onClearFilter() }
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

    private val systemToggle = JToggleButton(EyeGlyphIcon(AdbToolboxTheme.Colors.textDim)).apply {
        preferredSize = Dimension(AdbToolboxTheme.Sizes.iconButton, AdbToolboxTheme.Sizes.iconButton)
        toolTipText = "Show system packages"
        isContentAreaFilled = false
        isFocusPainted = false
        border = BorderFactory.createEmptyBorder()
        getAccessibleContext().accessibleName = "Show system packages"
        addActionListener {
            onToggleSystemPackages()
            refreshSystemTogglePresentation()
        }
        presentSystemToggle(this, active = false)
    }

    private val toolbar = flexRow(AdbToolboxTheme.Spacing.s3, searchFieldWrap, systemToggle, fill = searchFieldWrap).apply {
        isOpaque = true
        background = AdbToolboxTheme.Colors.bg
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.s4),
        )
    }

    private val list = AppsVirtualList(onSelect = onSelect)
    private val scrollPane = JScrollPane(list).apply {
        border = BorderFactory.createEmptyBorder()
        viewport.background = AdbToolboxTheme.Colors.bg
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
    private val clearFilterLink = DesignButton("Clear filter", DesignButtonStyle.LINK).apply {
        alignmentX = Component.CENTER_ALIGNMENT
        addActionListener { onClearFilter() }
    }
    private val emptyStatePanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(34), AdbToolboxTheme.Spacing.s6, AdbToolboxTheme.Spacing.s6, AdbToolboxTheme.Spacing.s6)
        add(emptyStateTitleLabel)
        add(javax.swing.Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
        add(emptyStateBodyLabel)
        add(javax.swing.Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
        add(clearFilterLink)
        isVisible = false
    }

    private val centerContainer = JPanel(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.bg
        add(scrollPane, BorderLayout.CENTER)
        add(emptyStatePanel, BorderLayout.NORTH)
    }

    // ---- pinned action footer (`design/README.md` §4) ----

    private val selectedAppLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.sectionTitle.deriveFont(JBUI.scale(12f))
        foreground = AdbToolboxTheme.Colors.text
    }
    private val selectedAppPackageLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val selectedAppRow = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        add(selectedAppLabel)
        add(selectedAppPackageLabel)
    }

    private val restartButton = DesignButton("Restart", DesignButtonStyle.PRIMARY).apply {
        toolTipText = RESTART_TOOLTIP
        isEnabled = false
        addActionListener { onRestart() }
    }
    private val forceStopButton = DesignButton("Force-stop", DesignButtonStyle.SECONDARY).apply {
        toolTipText = FORCE_STOP_TOOLTIP
        isEnabled = false
        addActionListener { onForceStop() }
    }
    private val launchButton = DesignButton("Launch", DesignButtonStyle.SECONDARY).apply {
        toolTipText = LAUNCH_TOOLTIP
        isEnabled = false
        addActionListener { onLaunch() }
    }
    private val actionRow = flexRow(AdbToolboxTheme.Spacing.s3, restartButton, forceStopButton, launchButton).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
    }

    private val destructiveLabel = JBLabel("DESTRUCTIVE").apply {
        font = AdbToolboxTheme.Typography.groupLabel
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val clearDataButton = DesignButton("Clear data", DesignButtonStyle.DANGER).apply {
        toolTipText = CLEAR_DATA_TOOLTIP
        isEnabled = false
        addActionListener { onClearData() }
    }
    private val uninstallButton = DesignButton("Uninstall", DesignButtonStyle.DANGER).apply {
        toolTipText = UNINSTALL_TOOLTIP
        isEnabled = false
        addActionListener { onUninstall() }
    }

    // `dangerZoneStyle`: margin 0 10px, 1px dashed top border, `padding: 8px 10px 0`; the label
    // takes `margin-right: auto`, so both destructive buttons sit on the trailing edge.
    private val destructiveZone = run {
        val spacer = flexSpacer()
        flexRow(AdbToolboxTheme.Spacing.s3, destructiveLabel, spacer, clearDataButton, uninstallButton, fill = spacer)
    }.apply {
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset),
            BorderFactory.createCompoundBorder(
                DashedTopBorder(AdbToolboxTheme.Colors.border),
                JBUI.Borders.empty(AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.sectionInset, 0, 0),
            ),
        )
    }

    private val actionFooterPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.panel
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(AdbToolboxTheme.Spacing.s4, 0, JBUI.scale(10), 0),
        )
        add(selectedAppRow)
        add(javax.swing.Box.createVerticalStrut(AdbToolboxTheme.Spacing.s4))
        add(actionRow)
        add(javax.swing.Box.createVerticalStrut(AdbToolboxTheme.Spacing.s4))
        add(destructiveZone)
    }

    init {
        add(toolbar, BorderLayout.NORTH)
        add(centerContainer, BorderLayout.CENTER)
        add(actionFooterPanel, BorderLayout.SOUTH)
    }

    /** Test-only visibility hooks so a test can drive real Swing interactions without a live display. */
    internal val searchFieldForTest: JTextField get() = searchField
    internal val clearQueryButtonForTest: JButton get() = clearQueryButton
    internal val systemToggleForTest: JToggleButton get() = systemToggle
    internal val listForTest: AppsVirtualList get() = list
    internal val emptyStateTextForTest: String get() = "${emptyStateTitleLabel.text} ${emptyStateBodyLabel.text}".trim()
    internal val clearFilterLinkForTest: JButton get() = clearFilterLink
    internal val selectedAppLabelForTest: String get() = selectedAppLabel.text
    internal val selectedAppPackageForTest: String get() = selectedAppPackageLabel.text
    internal val restartButtonForTest: JButton get() = restartButton
    internal val forceStopButtonForTest: JButton get() = forceStopButton
    internal val launchButtonForTest: JButton get() = launchButton
    internal val clearDataButtonForTest: JButton get() = clearDataButton
    internal val uninstallButtonForTest: JButton get() = uninstallButton
    internal val destructiveLabelForTest: String get() = destructiveLabel.text

    fun update(state: AppsViewState) {
        if (searchField.text != state.query) searchField.text = state.query
        clearQueryButton.isVisible = state.query.isNotEmpty()
        if (systemToggle.isSelected != state.showSystemPackages) {
            systemToggle.isSelected = state.showSystemPackages
            refreshSystemTogglePresentation()
        }

        list.virtualModel.apply(state.rows)
        list.syncSelectionFromRows()

        emptyStatePanel.isVisible = state.isFilteredEmpty || state.isGenuinelyEmpty
        clearFilterLink.isVisible = state.isFilteredEmpty
        when {
            state.errorMessage != null -> {
                emptyStateTitleLabel.text = state.errorMessage
                emptyStateBodyLabel.text = ""
            }
            state.isFilteredEmpty -> {
                emptyStateTitleLabel.text = "No packages match “${state.query}”"
                emptyStateBodyLabel.text = "Search matches package name and app label."
            }
            state.isGenuinelyEmpty -> {
                emptyStateTitleLabel.text = "No packages found"
                emptyStateBodyLabel.text = ""
            }
            else -> {
                emptyStateTitleLabel.text = ""
                emptyStateBodyLabel.text = ""
            }
        }

        val selectedRow = state.rows.firstOrNull { it.packageName == state.selectedPackageName }
        selectedAppLabel.text = selectedRow?.label.orEmpty()
        selectedAppPackageLabel.text = selectedRow?.packageName.orEmpty()
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
        val reason = disabledReason(state.controlPolicy, state.selectedPackageName, state.busy)
        restartButton.toolTipText = RESTART_TOOLTIP.withDisabledReason(reason)
        forceStopButton.toolTipText = FORCE_STOP_TOOLTIP.withDisabledReason(reason)
        launchButton.toolTipText = LAUNCH_TOOLTIP.withDisabledReason(reason)
    }

    /** Reflects the independent confirm-before-command workflow onto its destructive control. */
    fun updateClearData(state: ClearDataViewState) {
        clearDataButton.isEnabled = state.actionEnabled
        clearDataButton.toolTipText = CLEAR_DATA_TOOLTIP.withDisabledReason(
            disabledReason(state.controlPolicy, state.selectedPackageName, state.busy),
        )
    }

    /** Reflects task 025's independent confirm-before-command workflow onto its destructive control. */
    fun updateUninstall(state: UninstallViewState) {
        uninstallButton.isEnabled = state.actionEnabled
        uninstallButton.toolTipText = UNINSTALL_TOOLTIP.withDisabledReason(
            disabledReason(state.controlPolicy, state.selectedPackageName, state.busy),
        )
    }

    /** Test/disposal seam: releases [list]'s own listeners. Coordinators call this from their own `dispose()`. */
    fun disposePanel() {
        list.disposeList()
    }

    private fun refreshSystemTogglePresentation() {
        presentSystemToggle(systemToggle, systemToggle.isSelected)
    }

    private fun presentSystemToggle(button: JToggleButton, active: Boolean) {
        button.icon = EyeGlyphIcon(if (active) AdbToolboxTheme.Colors.accent else AdbToolboxTheme.Colors.textDim)
        button.background = if (active) AdbToolboxTheme.Colors.accentBg else AdbToolboxTheme.Colors.panel
        button.border = if (active) {
            SolidChipBorder(AdbToolboxTheme.Colors.accentBorder)
        } else {
            BorderFactory.createEmptyBorder()
        }
        button.isOpaque = active
    }

    private companion object {
        val RESTART_TOOLTIP: String get() = ShortcutHints.withAction("Force-stop, then launch the main activity", "AdbToolbox.RestartApp")
        const val FORCE_STOP_TOOLTIP = "am force-stop — leaves data intact"
        const val LAUNCH_TOOLTIP = "monkey launch of the main activity"
        const val CLEAR_DATA_TOOLTIP = "Deletes databases, prefs and caches. Cannot be undone."
        const val UNINSTALL_TOOLTIP = "Removes the app and all its data. Cannot be undone."

        /** `design/README.md` Interactions: every device-mutating control "keeps its tooltip and gains
         * the reason" it is disabled — extended here past the no-device case to the two other reasons
         * these action buttons independently disable for (no package selected, an action already
         * running), so the tooltip always names the actual blocker rather than staying silent. */
        fun disabledReason(controlPolicy: ControlPolicy, selectedPackageName: String?, busy: Boolean): String? = when {
            controlPolicy !is ControlPolicy.Enabled -> "Connect a device to use this"
            selectedPackageName == null -> "Select an app to use this"
            busy -> "An action is already running for this app"
            else -> null
        }

        fun String.withDisabledReason(reason: String?): String = if (reason == null) this else "$this — $reason"
    }
}

/**
 * The prototype's inline "Show system packages" glyph (`design/designs/ADB Toolbox
 * Plugin.dc.html`'s `eyeIconStyle`: a 12×8 hollow ellipse, 1.4px stroke) — there is no delivered
 * SVG for it (unlike the toolbar/action icons under `design/icons/actions/`), so it is redrawn with
 * plain [Graphics2D] primitives the same way [dev.acme.adbtoolbox.intellij.ui.common.RailGlyphIcon]
 * redraws the rail glyphs task 042's icon set does not include.
 */
private class EyeGlyphIcon(private val color: Color) : javax.swing.Icon {
    override fun getIconWidth(): Int = JBUI.scale(12)
    override fun getIconHeight(): Int = JBUI.scale(8)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val strokeWidth = JBUI.scale(1.4f)
            g2.stroke = BasicStroke(strokeWidth)
            val inset = strokeWidth / 2f
            g2.draw(
                java.awt.geom.Ellipse2D.Float(
                    x + inset,
                    y + inset,
                    iconWidth - strokeWidth,
                    iconHeight - strokeWidth,
                ),
            )
        } finally {
            g2.dispose()
        }
    }
}

/** The destructive group's dashed top divider (`design/README.md` §4: "a 1px dashed top border"). */
private class DashedTopBorder(private val color: Color) : AbstractBorder() {
    override fun getBorderInsets(c: Component) = java.awt.Insets(JBUI.scale(1), 0, 0, 0)

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = color
            g2.stroke = BasicStroke(
                JBUI.scale(1f),
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND,
                1f,
                floatArrayOf(JBUI.scale(3f), JBUI.scale(2f)),
                0f,
            )
            g2.drawLine(x, y, x + width, y)
        } finally {
            g2.dispose()
        }
    }
}
