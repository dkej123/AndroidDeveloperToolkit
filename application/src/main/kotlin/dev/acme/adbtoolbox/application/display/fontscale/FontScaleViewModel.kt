package dev.acme.adbtoolbox.application.display.fontscale

import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.fontscale.FontScalePresets
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleValidationResult
import dev.acme.adbtoolbox.domain.display.fontscale.validateFontScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns font-scale state for the currently eligible device (task 026): read, validate, apply,
 * read back, and reset. [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-
 * injection seam as [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel] (ADR 0004).
 *
 * Every write (apply/reset) is followed by a mandatory readback, and [state] is updated from that
 * readback's *actual* value — never the value that was requested — so a device that clamps, rejects,
 * or rounds a value is reported honestly (task 026's "Override state reflects readback, never the
 * requested value alone").
 *
 * A single [ops] [MutableSharedFlow] consumed with [collectLatest] is both this task's debounce/
 * serialization mechanism for rapid intents *and* its stale-serial suppression mechanism: a device
 * switch enqueues a new [Op.Load] the same way a new [FontScaleIntent] does, so collecting the
 * *latest* op alone guarantees a slow write/readback for a previously-selected device is always
 * cancelled by whatever superseded it, rather than racing to completion and clobbering newer state.
 * The `eligibleSerial` check inside [perform] is a second, defensive guard for the narrow window
 * between a cancellable suspend point resuming and the cancellation actually taking effect.
 */
class FontScaleViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    commandContext: StateFlow<DeviceCommandContext>,
    private val overrides: FontScaleOverrides = FontScaleOverrides(),
    private val useCase: FontScaleUseCase = FontScaleUseCase(transport, overrides),
) {
    private val _state = MutableStateFlow<FontScaleState>(FontScaleState.Loading)
    val state: StateFlow<FontScaleState> = _state.asStateFlow()

    /** Registers with a `DeviceContextAggregator` (task 014) so the status bar reflects font scale. */
    val overrideContributor get() = overrides

    /** This feature's task 041 coordinator-facing port — see [FontScaleOverrideResetUseCase]. */
    fun overrideResetUseCase(): FontScaleOverrideResetUseCase = FontScaleOverrideResetUseCase(useCase, overrides)

    private sealed interface Op {
        val serial: DeviceSerial

        data class Load(override val serial: DeviceSerial) : Op
        data class Write(override val serial: DeviceSerial, val target: Double) : Op
    }

    // Replay the latest op so the initial Load emitted by the command-context collector cannot be
    // lost when that collector starts before this flow's collector. DROP_OLDEST (rather than the
    // default SUSPEND) also ensures a rapid second `tryEmit` always keeps the newest operation.
    private val ops = MutableSharedFlow<Op>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var eligibleSerial: DeviceSerial? = null

    init {
        scope.launch(dispatchers.default) {
            commandContext.collect { context ->
                when (context) {
                    is DeviceCommandContext.Eligible -> {
                        eligibleSerial = context.serial
                        ops.tryEmit(Op.Load(context.serial))
                    }
                    is DeviceCommandContext.Disabled -> {
                        eligibleSerial = null
                        _state.value = FontScaleState.Loading
                    }
                }
            }
        }
        scope.launch(dispatchers.io) {
            ops.collectLatest(::perform)
        }
    }

    fun handle(intent: FontScaleIntent) {
        val serial = eligibleSerial ?: return
        when (intent) {
            is FontScaleIntent.Apply -> applyOrReject(serial, intent.value)
            FontScaleIntent.Reset -> ops.tryEmit(Op.Write(serial, FontScalePresets.DEFAULT))
            FontScaleIntent.Retry -> ops.tryEmit(Op.Load(serial))
        }
    }

    private fun applyOrReject(serial: DeviceSerial, value: Double) {
        when (val validation = validateFontScale(value)) {
            is FontScaleValidationResult.Valid -> ops.tryEmit(Op.Write(serial, validation.value))
            is FontScaleValidationResult.OutOfRange -> _state.value = FontScaleState.Error(
                current = currentValueOrNull(),
                message = "Value must be between ${validation.min} and ${validation.max}",
            )
        }
    }

    private fun currentValueOrNull(): Double? = when (val current = _state.value) {
        is FontScaleState.Idle -> current.current
        is FontScaleState.Applying -> current.current
        is FontScaleState.Error -> current.current
        FontScaleState.Loading -> null
    }

    private suspend fun perform(op: Op) {
        if (eligibleSerial != op.serial) return
        when (op) {
            is Op.Load -> {
                _state.value = FontScaleState.Loading
                applyResult(op.serial, useCase.read(op.serial))
            }
            is Op.Write -> {
                _state.value = FontScaleState.Applying(current = currentValueOrNull(), target = op.target)
                applyResult(op.serial, useCase.write(op.serial, op.target))
            }
        }
    }

    private fun applyResult(serial: DeviceSerial, result: FontScaleResult) {
        if (eligibleSerial != serial) return
        _state.value = when (result) {
            is FontScaleResult.Success -> FontScaleState.Idle(result.value)
            is FontScaleResult.Malformed -> FontScaleState.Error(currentValueOrNull(), "Unexpected device output: ${result.raw}")
            is FontScaleResult.Failed -> FontScaleState.Error(currentValueOrNull(), result.message)
        }
    }
}
