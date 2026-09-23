package dev.acme.adbtoolbox.intellij.ui.recording

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
import java.awt.CardLayout
import java.awt.Font
import javax.swing.JButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** Task 044 visual binding for task 020's existing recording toggle/state. */
class RecordingView(
    private val viewModel: RecordingViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val onRecordingVisibilityChanged: (Boolean) -> Unit = {},
) : JBPanel<RecordingView>(CardLayout()), Disposable {

    private val recordButton = DesignButton("Record", DesignButtonStyle.SECONDARY).apply {
        toolTipText = RECORD_TOOLTIP
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    private val stopButton = DesignButton("Stop & save", DesignButtonStyle.DANGER).apply {
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    val toggleButton: JButton get() = if (recordingBanner.isVisible) stopButton else recordButton

    val statusLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(Font.BOLD)
        foreground = AdbToolboxTheme.Colors.red
        icon = StatusDotIcon(AdbToolboxTheme.Colors.red, filled = true)
        iconTextGap = JBUI.scale(7)
    }

    // Vertically centered so the idle button lines up with Screenshot in the shared capture row.
    private val idleRow = flexRow(0, recordButton)

    // `recordingRowStyle`: radius 5, `redBg` + 1px `redBorder`, `padding: 6px 8px`, gap 7.
    val recordingBanner = RoundedSurface(AdbToolboxTheme.Colors.redBg, AdbToolboxTheme.Colors.redBorder).apply {
        layout = FlexRowLayout(JBUI.scale(7))
        border = JBUI.Borders.empty(6, 8)
        add(statusLabel, FlexRowLayout.FILL)
        add(stopButton)
    }

    init {
        isOpaque = false
        add(idleRow, IDLE)
        add(recordingBanner, RECORDING)
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
        (layout as CardLayout).show(this, if (isRecording) RECORDING else IDLE)
        recordingBanner.isVisible = isRecording
        idleRow.isVisible = !isRecording
        onRecordingVisibilityChanged(isRecording)

        recordButton.text = when (state.presentationState) {
            RecordingPresentationState.Starting -> "Starting…"
            RecordingPresentationState.Stopping -> "Stopping…"
            RecordingPresentationState.Pulling -> "Saving…"
            else -> "Record"
        }
        recordButton.toolTipText = (state.presentationState as? RecordingPresentationState.Error)?.message
            ?: RECORD_TOOLTIP.withDisabledReason(enabled)
        statusLabel.text = state.elapsedLabel?.let { "Recording · $it" } ?: ""
    }

    override fun dispose() = scope.cancel()

    private companion object {
        const val IDLE = "idle"
        const val RECORDING = "recording"
        const val RECORD_TOOLTIP = "Record the screen — max 3 minutes per adb"

        /** `design/README.md` Interactions: every device-mutating control "keeps its tooltip and
         * gains the reason" it is disabled — the same [dev.acme.adbtoolbox.intellij.apps
         * .AppsPanel.disabledReason] extension, scoped to this view's single "no eligible device"
         * blocker. */
        fun String.withDisabledReason(enabled: Boolean): String =
            if (enabled) this else "$this — Connect a device to use this"
    }
}
