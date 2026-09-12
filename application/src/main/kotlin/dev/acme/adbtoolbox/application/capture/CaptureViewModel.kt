package dev.acme.adbtoolbox.application.capture

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.controlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackAction
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
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
 * is handled, so the [dev.acme.adbtoolbox.domain.adb.DeviceSerial] a capture targets is fixed for
 * that capture's whole lifetime: a later device-selection change while the capture is still running
 * cannot reattribute it (task 019's TDD plan, mirrored by [CaptureScreenshotUseCase]'s own serial
 * parameter).
 *
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel].
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
            CaptureIntent.CaptureScreenshot -> captureScreenshot()
            CaptureIntent.RevealLastCapture -> revealLastCapture()
        }
    }

    private fun captureScreenshot() {
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
            val result = withContext(dispatchers.io) { captureScreenshotUseCase.capture(serial) }
            _state.update { it.copy(isCapturing = false) }
            when (result) {
                is CaptureScreenshotResult.Success -> {
                    _state.update { it.copy(lastCapture = result.location) }
                    feedback.handle(
                        FeedbackIntent.Post(
                            FeedbackMessage(
                                id = "capture-success-${result.location.displayPath}",
                                text = "Saved ${result.location.displayPath}",
                                severity = FeedbackSeverity.Success,
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
