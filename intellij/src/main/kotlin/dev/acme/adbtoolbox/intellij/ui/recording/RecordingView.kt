package dev.acme.adbtoolbox.intellij.ui.recording

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
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
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

    private val recordButton = JButton("Record").apply {
        preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.secondaryButton)
        toolTipText = RECORD_TOOLTIP
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    private val stopButton = JButton("Stop & save").apply {
        foreground = AdbToolboxTheme.Colors.red
        border = SolidChipBorder(AdbToolboxTheme.Colors.redBorder)
        isContentAreaFilled = false
        preferredSize = Dimension(preferredSize.width, JBUI.scale(24))
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    val toggleButton: JButton get() = if (recordingBanner.isVisible) stopButton else recordButton

    val statusLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(Font.BOLD)
        foreground = AdbToolboxTheme.Colors.red
        icon = StatusDotIcon(AdbToolboxTheme.Colors.red, filled = true)
    }

    private val idleRow = JBPanel<Nothing>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        add(recordButton)
    }

    val recordingBanner = JBPanel<Nothing>(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.redBg
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.redBorder),
            BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
        add(statusLabel, BorderLayout.CENTER)
        add(stopButton, BorderLayout.EAST)
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
        recordButton.toolTipText = (state.presentationState as? RecordingPresentationState.Error)?.message ?: RECORD_TOOLTIP
        statusLabel.text = state.elapsedLabel?.let { "Recording · $it" } ?: ""
    }

    override fun dispose() = scope.cancel()

    private companion object {
        const val IDLE = "idle"
        const val RECORDING = "recording"
        const val RECORD_TOOLTIP = "Record the screen — max 3 minutes per adb"
    }
}
