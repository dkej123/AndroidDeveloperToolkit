package dev.acme.adbtoolbox.application.wifi

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.wifi.PairingCode
import dev.acme.adbtoolbox.domain.wifi.PairingCodeResult
import dev.acme.adbtoolbox.domain.wifi.WifiEndpoint
import dev.acme.adbtoolbox.domain.wifi.WifiEndpointResult
import dev.acme.adbtoolbox.domain.wifi.WifiPairingInput
import dev.acme.adbtoolbox.domain.wifi.WifiPairingInputPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Task 039's device-bar-triggered pairing flow: collects raw input via [inputPort] (a native
 * IntelliJ dialog in production, `:intellij`'s own concern), validates it, then drives
 * [useCase]'s pair-then-connect sequence off [dispatchers]' `io` context so a multi-second wireless
 * handshake never touches EDT. [scope]/[dispatchers] follow the same feature-local-child-scope/
 * dispatcher-injection seam as every other ViewModel in this module (ADR 0004); [scope] is owned by
 * the caller.
 *
 * **No implicit device**: pairing/connect are server-scoped (ADR 0005) — this class never reads or
 * requires a selected device; the endpoints the user types are the only targets.
 *
 * **Secrecy**: the parsed [PairingCode] and the raw code text only ever exist as local variables
 * inside [launchPairingFlow]'s coroutine. Neither [WifiPairingViewState] nor any class-level field
 * here ever holds one, so there is nothing to "clear" beyond that coroutine returning (cancellation
 * included) — the value is eligible for collection the moment the call completes, and it is never
 * passed to [feedback] or logged.
 *
 * **Cancellation**: [handle] with [WifiPairingIntent.Cancel] cancels the in-flight job, if any;
 * [dispose] does the same so a project close mid-pairing tears the call down rather than leaking it.
 */
class WifiPairingViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val inputPort: WifiPairingInputPort,
    private val useCase: WifiPairingUseCase,
    private val feedback: FeedbackViewModel,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(WifiPairingViewState())
    val state: StateFlow<WifiPairingViewState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun handle(intent: WifiPairingIntent) {
        when (intent) {
            WifiPairingIntent.Launch -> launchPairingFlow()
            WifiPairingIntent.Cancel -> activeJob?.cancel()
        }
    }

    private fun launchPairingFlow() {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            _state.value = WifiPairingViewState(isBusy = true)
            try {
                when (val input = withContext(dispatchers.main) { inputPort.collectPairingInput() }) {
                    WifiPairingInput.Cancelled -> Unit
                    is WifiPairingInput.Submitted -> processSubmission(input)
                }
            } finally {
                _state.value = WifiPairingViewState(isBusy = false)
            }
        }
    }

    private suspend fun processSubmission(input: WifiPairingInput.Submitted) {
        val pairingEndpointResult = WifiEndpoint.parse(input.pairingEndpointText)
        val codeResult = PairingCode.parse(input.pairingCodeText)
        val connectEndpointResult = WifiEndpoint.parse(input.connectEndpointText)
        val invalidReason = (pairingEndpointResult as? WifiEndpointResult.Invalid)?.reason
            ?: (codeResult as? PairingCodeResult.Invalid)?.reason
            ?: (connectEndpointResult as? WifiEndpointResult.Invalid)?.reason
        if (invalidReason != null) {
            post(FeedbackSeverity.Error, invalidReason)
            return
        }

        val outcome = withContext(dispatchers.io) {
            useCase.pairAndConnect(
                (pairingEndpointResult as WifiEndpointResult.Valid).endpoint,
                (codeResult as PairingCodeResult.Valid).code,
                (connectEndpointResult as WifiEndpointResult.Valid).endpoint,
            )
        }
        report(outcome)
    }

    private fun report(outcome: WifiPairingOutcome) {
        when (outcome) {
            WifiPairingOutcome.Success -> post(FeedbackSeverity.Success, "Paired and connected")
            is WifiPairingOutcome.PairFailed -> post(FeedbackSeverity.Error, "Pairing failed: ${outcome.reason}")
            is WifiPairingOutcome.ConnectFailed -> post(FeedbackSeverity.Error, "Connect failed: ${outcome.reason}")
            WifiPairingOutcome.TimedOut -> post(FeedbackSeverity.Error, "Wi-Fi pairing timed out")
            WifiPairingOutcome.Cancelled -> post(FeedbackSeverity.Error, "Wi-Fi pairing cancelled")
        }
    }

    private fun post(severity: FeedbackSeverity, text: String) {
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(id = "wifi-pairing-${severity.name.lowercase()}-${clock.now()}", text = text, severity = severity),
            ),
        )
    }

    /** Cancels any in-flight pairing call — a project/tool-window disposal never leaves one running. */
    fun dispose() {
        activeJob?.cancel()
    }
}
