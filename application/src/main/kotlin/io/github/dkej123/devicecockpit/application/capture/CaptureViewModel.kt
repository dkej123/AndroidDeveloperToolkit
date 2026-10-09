package io.github.dkej123.devicecockpit.application.capture

import io.github.dkej123.devicecockpit.application.feedback.FeedbackIntent
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.capture.RevealInFileManager
import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.devicecontext.controlPolicy
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackAction
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackMessage
import io.github.dkej123.devicecockpit.domain.feedback.FeedbackSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * The feature ViewModel for task 019's minimal Device-view screenshot binding (ADR 0004's MVI
 * seam): a thin `:intellij` view renders [state] and forwards clicks to [handle]. [selectedDeviceState]
 * is read live for [CaptureViewState.controlPolicy] (task 014, mirroring every other device-mutating
 * control) and read again — synchronously, via `.value` — at the moment [CaptureIntent.CaptureScreenshot]
 * is handled, so the [io.github.dkej123.devicecockpit.domain.adb.DeviceSerial] a capture targets is fixed for
 * that capture's whole lifetime: a later device-selection change while the capture is still running
 * cannot reattribute it (task 019's TDD plan, mirrored by [CaptureScreenshotUseCase]'s own serial
 * parameter).
 *
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * [io.github.dkej123.devicecockpit.application.device.SelectedDeviceViewModel].
 */
class CaptureViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val captureScreenshotUseCase: CaptureScreenshotUseCase,
    private val revealInFileManager: RevealInFileManager,
    private val feedback: FeedbackViewModel,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(CaptureViewState(controlPolicy = selectedDeviceState.value.controlPolicy()))
    val state: StateFlow<CaptureViewState> = _state.asStateFlow()

    init {
        selectedDeviceState
            .onEach { current -> _state.update { it.copy(controlPolicy = current.controlPolicy()) } }
            .launchIn(scope)
    }

    fun handle(intent: CaptureIntent) {
        when (intent) {
            CaptureIntent.CaptureScreenshot -> captureScreenshot(ScreenshotMode.Visible)
            CaptureIntent.CaptureFullScreenshot -> captureScreenshot(ScreenshotMode.FullContent)
            CaptureIntent.RevealLastCapture -> revealLastCapture()
        }
    }

    private fun captureScreenshot(mode: ScreenshotMode) {
        val context = selectedDeviceState.value.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            feedback.handle(
                FeedbackIntent.Post(
                    FeedbackMessage(
                        id = "capture-no-device-${clock.now()}",
                        text = "No eligible device selected",
                        severity = FeedbackSeverity.Warning,
                    ),
                ),
            )
            return
        }
        val serial = context.serial
        _state.update { it.copy(isCapturing = true) }
        scope.launch {
            val result = withContext(dispatchers.io) { captureScreenshotUseCase.capture(serial, mode) }
            _state.update { it.copy(isCapturing = false) }
            when (result) {
                is CaptureScreenshotResult.Success -> {
                    _state.update { it.copy(lastCapture = result.location) }
                    feedback.handle(
                        FeedbackIntent.Post(
                            FeedbackMessage(
                                id = "capture-success-${result.location.displayPath}",
                                text = buildString {
                                    append("Saved ${result.location.displayPath}")
                                    if (result.copiedToClipboard) append(" · copied to clipboard")
                                    if (result.truncated) append(" — the content is longer and was cut off")
                                },
                                severity = if (result.truncated) FeedbackSeverity.Warning else FeedbackSeverity.Success,
                                action = FeedbackAction("Reveal") { revealInFileManager.reveal(result.location) },
                            ),
                        ),
                    )
                }

                is CaptureScreenshotResult.Failure -> {
                    feedback.handle(
                        FeedbackIntent.Post(
                            FeedbackMessage(
                                id = "capture-error-${clock.now()}",
                                text = result.reason,
                                severity = FeedbackSeverity.Error,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun revealLastCapture() {
        state.value.lastCapture?.let { revealInFileManager.reveal(it) }
    }
}
