package dev.acme.adbtoolbox.intellij.ui.mirroring

import dev.acme.adbtoolbox.intellij.ui.common.ShortcutHints
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.WrappingText
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.mirroring.MirroringIntent
import dev.acme.adbtoolbox.application.mirroring.MirroringPresentationState
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** Task 044 visual binding for the existing mirroring intent/state contract. */
class MirroringView(
    private val viewModel: MirroringViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val openOptions: () -> Unit = {},
) : JBPanel<MirroringView>(BorderLayout()), Disposable {

    private val startButton = DesignButton("Start mirroring", DesignButtonStyle.PRIMARY).apply {
        toolTipText = START_TOOLTIP
        addActionListener { viewModel.handle(MirroringIntent.Toggle) }
    }

    private val stopButton = DesignButton("Stop", DesignButtonStyle.DANGER).apply {
        addActionListener { viewModel.handle(MirroringIntent.Toggle) }
    }

    val toggleButton: JButton get() = if (runningBanner.isVisible) stopButton else startButton

    val optionsButton = JButton(AdbToolboxIcons.Actions.options).apply {
        text = ""
        preferredSize = Dimension(AdbToolboxTheme.Sizes.iconButton, AdbToolboxTheme.Sizes.iconButton)
        toolTipText = OPTIONS_TOOLTIP
        getAccessibleContext().accessibleName = "Mirroring options"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        addActionListener { openOptions() }
    }

    private val idleRow = flexRow(AdbToolboxTheme.Spacing.s3, startButton, optionsButton)

    val runningLabel = JBLabel("Mirroring · Running").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.brand
        icon = StatusDotIcon(AdbToolboxTheme.Colors.brand, filled = true)
    }

    // `runningRowStyle`: radius 5, `brandBg` + 1px `brandBorder`, `padding: 6px 8px`, gap 7.
    val runningBanner = RoundedSurface(AdbToolboxTheme.Colors.brandBg, AdbToolboxTheme.Colors.brandBorder).apply {
        layout = FlexRowLayout(JBUI.scale(7))
        border = JBUI.Borders.empty(6, 8)
        add(runningLabel, FlexRowLayout.FILL)
        add(stopButton)
    }

    private val stateCards = JBPanel<Nothing>(CardLayout()).apply {
        isOpaque = false
        add(idleRow, IDLE)
        add(runningBanner, RUNNING)
    }

    // `helpText`: 10.5px `textFaint`, wraps to the section width; 6px section gap above it.
    val helpLabel = WrappingText(IDLE_HELP, AdbToolboxTheme.Typography.caption, AdbToolboxTheme.Colors.textFaint).apply {
        border = JBUI.Borders.empty(6, 0, 2, 0)
    }

    init {
        isOpaque = false
        add(stateCards, BorderLayout.NORTH)
        add(helpLabel, BorderLayout.CENTER)
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    internal fun render(state: MirroringViewState) {
        val enabled = state.controlPolicy is ControlPolicy.Enabled
        startButton.isEnabled = enabled
        stopButton.isEnabled = enabled
        optionsButton.isEnabled = enabled
        optionsButton.toolTipText = OPTIONS_TOOLTIP.withDisabledReason(enabled)
        val isRunning = state.presentationState is MirroringPresentationState.Running
        (stateCards.layout as CardLayout).show(stateCards, if (isRunning) RUNNING else IDLE)
        runningBanner.isVisible = isRunning
        idleRow.isVisible = !isRunning

        startButton.text = when (state.presentationState) {
            MirroringPresentationState.Starting -> "Starting…"
            MirroringPresentationState.Stopping -> "Stopping…"
            else -> "Start mirroring"
        }
        startButton.toolTipText = (state.presentationState as? MirroringPresentationState.Error)?.message
            ?: START_TOOLTIP.withDisabledReason(enabled)
        helpLabel.text = if (isRunning) RUNNING_HELP else IDLE_HELP
    }

    override fun dispose() = scope.cancel()

    private companion object {
        const val IDLE = "idle"
        const val RUNNING = "running"
        val START_TOOLTIP: String get() = ShortcutHints.withAction("Start scrcpy for the selected device", "dev.acme.adbtoolbox.ToggleMirroring")
        const val OPTIONS_TOOLTIP = "Mirroring options — bitrate, resolution, stay awake"
        const val IDLE_HELP = "Launches Genymobile scrcpy. Turn on “stay awake” and “show touches” in options."
        const val RUNNING_HELP = "Window is open on your desktop. Closing it also stops this session."

        /** `design/README.md` Interactions: every device-mutating control "keeps its tooltip and
         * gains the reason" it is disabled — the same [dev.acme.adbtoolbox.intellij.apps
         * .AppsPanel.disabledReason] extension, scoped to this view's single "no eligible device"
         * blocker. */
        fun String.withDisabledReason(enabled: Boolean): String =
            if (enabled) this else "$this — Connect a device to use this"
    }
}
