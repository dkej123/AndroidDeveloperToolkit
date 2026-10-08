package dev.acme.adbtoolbox.intellij.ui.recording

import com.intellij.ui.scale.JBUIScale
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.recording.RecordingIntent
import dev.acme.adbtoolbox.application.recording.RecordingPresentationState
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.application.recording.RecordingViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.ToolButtonTone
import dev.acme.adbtoolbox.intellij.ui.common.ScreenToolButton
import java.awt.Font
import javax.swing.JButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The Screen section's recording controls (design §3 "Screen"), bound to task 020's recording
 * toggle/state. The view itself is the toolbar's Record button (red with a stop square while
 * recording); [statusRow] ("Recording · 00:42" + Stop & save) is mounted under the toolbar by
 * [dev.acme.adbtoolbox.intellij.recording.RecordingCoordinator].
 */
class RecordingView(
    private val viewModel: RecordingViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : JBPanel<RecordingView>(FlexRowLayout(0)), Disposable {

    val recordButton = ScreenToolButton(AdbToolboxIcons.Actions.record).apply {
        toolTipText = RECORD_TOOLTIP
        getAccessibleContext().accessibleName = RECORD_NAME
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    private val stopButton = DesignButton("Stop & save", DesignButtonStyle.DANGER).apply {
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    /** The control that toggles recording in the current state: Record while idle, Stop & save while recording. */
    val toggleButton: JButton get() = if (statusRow.isVisible) stopButton else recordButton

    val statusLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(Font.BOLD)
        foreground = AdbToolboxTheme.Colors.red
        icon = StatusDotIcon(AdbToolboxTheme.Colors.red, filled = true)
        iconTextGap = JBUIScale.scale(7)
    }

    // `recordingRowStyle`: margin 0 10, radius 5, `redBg` + 1px `redBorder`, `padding: 6px 8px`, gap 7.
    val recordingBanner = RoundedSurface(AdbToolboxTheme.Colors.redBg, AdbToolboxTheme.Colors.redBorder).apply {
        layout = FlexRowLayout(JBUIScale.scale(7))
        border = JBUI.Borders.empty(6, 8)
        add(statusLabel, FlexRowLayout.FILL)
        add(stopButton)
    }

    val statusRow: JBPanel<Nothing> = JBPanel<Nothing>(java.awt.BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        add(recordingBanner, java.awt.BorderLayout.CENTER)
        isVisible = false
    }

    init {
        isOpaque = false
        add(recordButton)
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    internal fun render(state: RecordingViewState) {
        val enabled = state.controlPolicy is ControlPolicy.Enabled
        recordButton.isEnabled = enabled &&
            state.presentationState !is RecordingPresentationState.Stopping &&
            state.presentationState !is RecordingPresentationState.Pulling
        stopButton.isEnabled = recordButton.isEnabled

        val isRecording = state.presentationState is RecordingPresentationState.Recording
        statusRow.isVisible = isRecording
        recordButton.tone = if (isRecording) ToolButtonTone.RED else null
        recordButton.glyph = if (isRecording) AdbToolboxIcons.Actions.stopRecording else AdbToolboxIcons.Actions.record
        recordButton.getAccessibleContext().accessibleName = if (isRecording) STOP_NAME else RECORD_NAME
        recordButton.toolTipText = (state.presentationState as? RecordingPresentationState.Error)?.message
            ?: when (state.presentationState) {
                RecordingPresentationState.Starting -> "Starting the recording…"
                RecordingPresentationState.Stopping -> "Stopping the recording…"
                RecordingPresentationState.Pulling -> "Saving the recording…"
                else -> (if (isRecording) STOP_TOOLTIP else RECORD_TOOLTIP).withDisabledReason(enabled)
            }
        statusLabel.text = state.elapsedLabel?.let { "Recording · $it" } ?: ""
        statusRow.parent?.let { it.revalidate(); it.repaint() }
    }

    override fun dispose() = scope.cancel()

    private companion object {
        const val RECORD_NAME = "Record"
        const val STOP_NAME = "Stop recording"
        const val RECORD_TOOLTIP = "Record the screen — max 3 minutes per adb"
        const val STOP_TOOLTIP = "Stop & save the recording"

        /** `design/README.md` Interactions: every device-mutating control "keeps its tooltip and
         * gains the reason" it is disabled. */
        fun String.withDisabledReason(enabled: Boolean): String =
            if (enabled) this else "$this — Connect a device to use this"
    }
}
