package io.github.dkej123.devicecockpit.intellij.ui.mirroring

import com.intellij.ui.scale.JBUIScale
import io.github.dkej123.devicecockpit.intellij.ui.common.ShortcutHints
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.FlexRowLayout
import io.github.dkej123.devicecockpit.intellij.ui.common.RoundedSurface
import io.github.dkej123.devicecockpit.intellij.ui.common.WrappingText
import io.github.dkej123.devicecockpit.intellij.ui.common.flexRow
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.mirroring.MirroringIntent
import io.github.dkej123.devicecockpit.application.mirroring.MirroringPresentationState
import io.github.dkej123.devicecockpit.application.mirroring.MirroringViewModel
import io.github.dkej123.devicecockpit.application.mirroring.MirroringViewState
import io.github.dkej123.devicecockpit.application.mirroring.ScrcpyAvailability
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.icons.AdbToolboxIcons
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.ScreenToolButton
import io.github.dkej123.devicecockpit.intellij.ui.common.StatusDotIcon
import io.github.dkej123.devicecockpit.intellij.ui.common.ToolButtonSegment
import io.github.dkej123.devicecockpit.intellij.ui.common.ToolButtonTone
import io.github.dkej123.devicecockpit.intellij.ui.common.VerticalStackLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The Screen section's mirroring controls (design §3 "Screen"), bound to the existing mirroring
 * intent/state contract. The view itself is the stack under the toolbar (inline options, scrcpy
 * help); [toolbarPart] (the Mirror split button) and [statusRow] ("Mirroring · …" + Stop) are
 * mounted by [io.github.dkej123.devicecockpit.intellij.mirroring.MirroringCoordinator] into their own rows.
 */
class MirroringView(
    private val viewModel: MirroringViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val optionsPanel: MirroringOptionsPanel? = null,
    private val openInstallGuide: () -> Unit = { BrowserUtil.browse(INSTALL_GUIDE_URL) },
) : JBPanel<MirroringView>(VerticalStackLayout { AdbToolboxTheme.Spacing.s3 }), Disposable {

    /** Monitor glyph; brand-tinted while running, when a click stops the session. */
    val mirrorButton = ScreenToolButton(AdbToolboxIcons.Actions.mirror, segment = ToolButtonSegment.MAIN).apply {
        toolTipText = START_TOOLTIP
        getAccessibleContext().accessibleName = START_NAME
        addActionListener { viewModel.handle(MirroringIntent.Toggle) }
    }

    /** The split button's caret: shows and hides [optionsPanel] in place. */
    val optionsButton = ScreenToolButton(null, segment = ToolButtonSegment.CARET).apply {
        toolTipText = OPTIONS_TOOLTIP
        getAccessibleContext().accessibleName = "Mirroring options"
        addActionListener { setOptionsOpen(!isOpen) }
    }

    val toolbarPart = flexRow(0, mirrorButton, optionsButton)

    private val stopButton = DesignButton("Stop", DesignButtonStyle.DANGER).apply {
        addActionListener { viewModel.handle(MirroringIntent.Toggle) }
    }

    /** The control that toggles the session in the current state: Mirror while idle, Stop while running. */
    val toggleButton: JButton get() = if (statusRow.isVisible) stopButton else mirrorButton

    val runningLabel = JBLabel("Mirroring · Running").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.brand
        icon = StatusDotIcon(AdbToolboxTheme.Colors.brand, filled = true)
    }

    // `runningRowStyle`: margin 0 10, radius 5, `brandBg` + 1px `brandBorder`, `padding: 6px 8px`, gap 7.
    val runningBanner = RoundedSurface(AdbToolboxTheme.Colors.brandBg, AdbToolboxTheme.Colors.brandBorder).apply {
        layout = FlexRowLayout(JBUIScale.scale(7))
        border = JBUI.Borders.empty(6, 8)
        add(runningLabel, FlexRowLayout.FILL)
        add(stopButton)
    }

    val statusRow: JBPanel<Nothing> = JBPanel<Nothing>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        add(runningBanner, BorderLayout.CENTER)
        isVisible = false
    }

    // Shown only while scrcpy is missing (user decision, 2026-09-29): why, and links to fix it.
    val helpLabel = WrappingText("", AdbToolboxTheme.Typography.caption, AdbToolboxTheme.Colors.textFaint).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        isVisible = false
    }
    val openSettingsLink = DesignButton("Open Settings", DesignButtonStyle.LINK).apply {
        addActionListener { viewModel.handle(MirroringIntent.OpenSettings) }
    }
    val recheckLink = DesignButton("Check again", DesignButtonStyle.LINK).apply {
        toolTipText = "Look for scrcpy again, e.g. after installing it"
        addActionListener { viewModel.handle(MirroringIntent.RecheckScrcpy) }
    }
    val installGuideLink = DesignButton("Install guide", DesignButtonStyle.LINK).apply {
        toolTipText = INSTALL_GUIDE_URL
        addActionListener { openInstallGuide() }
    }
    val scrcpyFixRow = flexRow(AdbToolboxTheme.Spacing.s4, openSettingsLink, recheckLink, installGuideLink).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        isVisible = false
    }

    init {
        isOpaque = false
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        optionsPanel?.let { add(it.apply { isVisible = false }) }
        add(helpLabel)
        add(scrcpyFixRow)
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun setOptionsOpen(open: Boolean) {
        optionsButton.isOpen = open && optionsPanel != null
        optionsPanel?.isVisible = optionsButton.isOpen
        revalidate()
        repaint()
    }

    internal fun render(state: MirroringViewState) {
        val enabled = state.controlPolicy is ControlPolicy.Enabled
        val missing = state.scrcpy as? ScrcpyAvailability.Missing
        val isRunning = state.presentationState is MirroringPresentationState.Running
        mirrorButton.isEnabled = enabled && (missing == null || isRunning)
        stopButton.isEnabled = enabled
        // The options only configure scrcpy: useless until it is installed (user request 2026-10-01).
        optionsButton.isEnabled = enabled && missing == null
        optionsButton.toolTipText = missing?.fixHint() ?: OPTIONS_TOOLTIP.withDisabledReason(enabled)
        if (!optionsButton.isEnabled) setOptionsOpen(false)

        mirrorButton.tone = if (isRunning) ToolButtonTone.BRAND else null
        mirrorButton.getAccessibleContext().accessibleName = if (isRunning) STOP_NAME else START_NAME
        mirrorButton.toolTipText = missing?.fixHint()
            ?: (state.presentationState as? MirroringPresentationState.Error)?.message
            ?: when (state.presentationState) {
                MirroringPresentationState.Starting -> "Starting scrcpy…"
                MirroringPresentationState.Stopping -> "Stopping scrcpy…"
                else -> (if (isRunning) STOP_TOOLTIP else START_TOOLTIP).withDisabledReason(enabled)
            }
        statusRow.isVisible = isRunning
        optionsPanel?.setMirroringRunning(isRunning)

        helpLabel.text = missing?.fixHint().orEmpty()
        helpLabel.isVisible = !isRunning && missing != null
        scrcpyFixRow.isVisible = !isRunning && missing != null
        statusRow.parent?.let { it.revalidate(); it.repaint() }
        revalidate()
        repaint()
    }

    override fun dispose() = scope.cancel()

    private companion object {
        const val START_NAME = "Start mirroring"
        const val STOP_NAME = "Stop mirroring"
        val START_TOOLTIP: String get() = ShortcutHints.withAction("Start mirroring — opens a scrcpy window", "dev.acme.adbtoolbox.ToggleMirroring")
        val STOP_TOOLTIP: String get() = ShortcutHints.withAction("Stop mirroring", "dev.acme.adbtoolbox.ToggleMirroring")
        const val OPTIONS_TOOLTIP = "Mirroring options — bitrate, resolution, stay awake, show touches"
        const val INSTALL_GUIDE_URL = "https://github.com/Genymobile/scrcpy#get-the-app"

        /** `design/README.md` Interactions: every device-mutating control "keeps its tooltip and
         * gains the reason" it is disabled. */
        fun String.withDisabledReason(enabled: Boolean): String =
            if (enabled) this else "$this — Connect a device to use this"
    }
}
