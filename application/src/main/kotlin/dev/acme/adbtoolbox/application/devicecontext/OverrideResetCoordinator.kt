package dev.acme.adbtoolbox.application.devicecontext

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.devicecontext.OverrideReapplyPersistence
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetUseCase
import dev.acme.adbtoolbox.domain.devicecontext.PendingReapplyOverride
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackAction
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives `design/README.md` §8's "N overrides · reset all" chip and the reconnect re-apply offer
 * (task 041) by delegating every feature's own mutation to its [OverrideResetUseCase] — never by
 * issuing an ADB command itself, and never for a serial other than the exact one it is currently
 * acting on. This coordinator owns no feature's override *state*; it only asks each
 * [OverrideResetUseCase] what it currently has applied ([OverrideResetUseCase.currentValue]) and
 * tells it to reset/re-apply — the same "feature packages own their own state" seam task 014's
 * [DeviceContextAggregator]/[dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor]
 * use.
 *
 * **Reset-all**: [resetAll] resets every feature with a currently-applied override for the
 * selected serial. A device switch (or a second [resetAll] call) cancels [activeResetJob] outright
 * so an in-flight reset for a serial that is no longer selected can never land against — or be
 * reported for — the newly-selected device.
 *
 * **Reconnect re-apply**: when the *selected* serial goes from online to non-online, this captures
 * every feature's current value as a [PendingReapplyOverride] and persists it via
 * [reapplyPersistence] ("persist only approved re-apply intent/state" — the persisted *offer*
 * material, never applied by itself). When that exact serial becomes online again, [pendingReapply]
 * still holding an entry for it triggers a single non-modal [FeedbackMessage] (task 013 — no
 * dialogs) carrying one recovery action; only [acceptReapply] (invoked by that action) ever writes
 * to the device, so a reconnect never mutates silently. [declineReapply] (or letting the toast
 * expire/be dismissed) simply discards the offer.
 */
class OverrideResetCoordinator(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val resetUseCases: List<OverrideResetUseCase>,
    private val reapplyPersistence: OverrideReapplyPersistence,
    private val feedback: FeedbackViewModel,
) {
    private var currentSerial: DeviceSerial? = selectedDeviceState.value.selectedSerialOrNull
    private var wasOnline: Boolean = selectedDeviceState.value is SelectedDeviceState.Online
    private var activeResetJob: Job? = null

    private val _pendingReapply = MutableStateFlow<Map<DeviceSerial, List<PendingReapplyOverride>>>(emptyMap())
    val pendingReapply: StateFlow<Map<DeviceSerial, List<PendingReapplyOverride>>> = _pendingReapply.asStateFlow()

    init {
        scope.launch(dispatchers.io) {
            _pendingReapply.value = runCatching { reapplyPersistence.read() }.getOrDefault(emptyMap())
        }
        scope.launch(dispatchers.default) {
            selectedDeviceState.collect(::onSelectedDeviceStateChanged)
        }
    }

    private fun onSelectedDeviceStateChanged(state: SelectedDeviceState) {
        val serial = state.selectedSerialOrNull
        val isOnline = state is SelectedDeviceState.Online

        if (serial != currentSerial) {
            activeResetJob?.cancel()
        } else if (serial != null && wasOnline && !isOnline) {
            captureDisconnectSnapshot(serial)
        } else if (serial != null && !wasOnline && isOnline && _pendingReapply.value.containsKey(serial)) {
            offerReapply(serial)
        }

        currentSerial = serial
        wasOnline = isOnline
    }

    /** Resets every currently-applied override for the selected device; a no-op with none applied. */
    fun resetAll() {
        val serial = currentSerial ?: return
        val targets = resetUseCases.filter { it.currentValue(serial) != null }
        if (targets.isEmpty()) return

        activeResetJob?.cancel()
        activeResetJob = scope.launch(dispatchers.io) {
            val outcomes = linkedMapOf<String, OverrideResetOutcome>()
            for (useCase in targets) {
                if (currentSerial != serial) break
                outcomes[useCase.featureId] = runCatchingOutcome { useCase.reset(serial) }
            }
            if (outcomes.isNotEmpty()) reportOutcomes(RESET_ALL_TOAST_ID, "Reset", outcomes)
        }
    }

    /** Re-applies [serial]'s pending re-apply overrides; a safe no-op if nothing is pending. */
    fun acceptReapply(serial: DeviceSerial) {
        val pending = _pendingReapply.value[serial] ?: return
        clearPending(serial)
        scope.launch(dispatchers.io) {
            val outcomes = linkedMapOf<String, OverrideResetOutcome>()
            for (item in pending) {
                val useCase = resetUseCases.find { it.featureId == item.featureId }
                outcomes[item.featureId] = if (useCase == null) {
                    OverrideResetOutcome.Failed("No re-apply available for ${item.featureId}")
                } else {
                    runCatchingOutcome { useCase.reapply(serial, item.value) }
                }
            }
            reportOutcomes(reapplyToastId(serial), "Re-applied", outcomes)
        }
    }

    /** Discards [serial]'s pending re-apply offer without touching the device; a safe no-op if none pending. */
    fun declineReapply(serial: DeviceSerial) {
        if (!_pendingReapply.value.containsKey(serial)) return
        clearPending(serial)
        feedback.handle(FeedbackIntent.Dismiss(reapplyToastId(serial)))
    }

    private fun captureDisconnectSnapshot(serial: DeviceSerial) {
        val pending = resetUseCases.mapNotNull { useCase ->
            useCase.currentValue(serial)?.let { PendingReapplyOverride(useCase.featureId, it) }
        }
        if (pending.isEmpty()) return
        _pendingReapply.value = _pendingReapply.value + (serial to pending)
        persistPending()
    }

    private fun offerReapply(serial: DeviceSerial) {
        val pending = _pendingReapply.value[serial] ?: return
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(
                    id = reapplyToastId(serial),
                    text = "Re-apply ${pending.size} previous override${if (pending.size == 1) "" else "s"} to this device?",
                    severity = FeedbackSeverity.Warning,
                    action = FeedbackAction("Re-apply") { acceptReapply(serial) },
                ),
            ),
        )
    }

    private fun clearPending(serial: DeviceSerial) {
        _pendingReapply.value = _pendingReapply.value - serial
        persistPending()
    }

    private fun persistPending() {
        val snapshot = _pendingReapply.value
        scope.launch(dispatchers.io) { runCatching { reapplyPersistence.write(snapshot) } }
    }

    private suspend fun runCatchingOutcome(block: suspend () -> OverrideResetOutcome): OverrideResetOutcome =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            OverrideResetOutcome.Failed(failure.message ?: "Unknown error")
        }

    private fun reportOutcomes(toastId: String, verb: String, outcomes: Map<String, OverrideResetOutcome>) {
        val failed = outcomes.filterValues { it is OverrideResetOutcome.Failed }
        val message = when {
            failed.isEmpty() -> FeedbackMessage(
                id = toastId,
                text = "$verb ${outcomes.size} override${if (outcomes.size == 1) "" else "s"}",
                severity = FeedbackSeverity.Success,
            )
            failed.size == outcomes.size -> FeedbackMessage(
                id = toastId,
                text = "Failed to ${verb.lowercase()} ${failed.size} override${if (failed.size == 1) "" else "s"}",
                severity = FeedbackSeverity.Error,
            )
            else -> FeedbackMessage(
                id = toastId,
                text = "$verb ${outcomes.size - failed.size} of ${outcomes.size} overrides — ${failed.keys.joinToString()} failed",
                severity = FeedbackSeverity.Error,
            )
        }
        feedback.handle(FeedbackIntent.Post(message))
    }

    private fun reapplyToastId(serial: DeviceSerial) = "reapply-${serial.value}"

    private companion object {
        const val RESET_ALL_TOAST_ID = "reset-all"
    }
}
