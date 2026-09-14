package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns display-density state for the currently eligible device (task 029), binding [useCase]
 * (task 027) to the same device-context-driven, stale-suppressing, single-latest-op shape
 * [dev.acme.adbtoolbox.application.display.fontscale.FontScaleViewModel] uses for font scale: a
 * device switch enqueues a fresh [Op.Load] the same way a new [DensityIntent] does, and collecting
 * only the *latest* op via [kotlinx.coroutines.flow.collectLatest] guarantees a slow apply for a
 * previously-selected device is always cancelled by whatever superseded it. The `eligibleSerial`
 * check inside [perform] is a second, defensive guard for the narrow window between a cancellable
 * suspend point resuming and the cancellation actually taking effect.
 *
 * [state] always reflects [useCase]'s readback — never the requested percent/dpi — since [useCase]
 * itself only ever records/returns an actual `wm density` reading.
 */
class DensityViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val useCase: DensityUseCase,
    commandContext: StateFlow<DeviceCommandContext>,
) {
    private val _state = MutableStateFlow<DensityViewState>(DensityViewState.Loading)
    val state: StateFlow<DensityViewState> = _state.asStateFlow()

    private sealed interface Op {
        val serial: DeviceSerial

        data class Load(override val serial: DeviceSerial) : Op
        data class ApplyPreset(override val serial: DeviceSerial, val percent: Int) : Op
        data class ApplyCustom(override val serial: DeviceSerial, val dpi: Int) : Op
        data class Reset(override val serial: DeviceSerial) : Op
    }

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
                        _state.value = DensityViewState.Loading
                    }
                }
            }
        }
        scope.launch(dispatchers.io) {
            ops.collectLatest(::perform)
        }
    }

    fun handle(intent: DensityIntent) {
        val serial = eligibleSerial ?: return
        when (intent) {
            is DensityIntent.ApplyPreset -> ops.tryEmit(Op.ApplyPreset(serial, intent.percent))
            is DensityIntent.ApplyCustom -> ops.tryEmit(Op.ApplyCustom(serial, intent.dpi))
            DensityIntent.Reset -> ops.tryEmit(Op.Reset(serial))
            DensityIntent.Retry -> ops.tryEmit(Op.Load(serial))
        }
    }

    private fun currentReadingOrNull(): DensityReading? = when (val current = _state.value) {
        is DensityViewState.Idle -> current.reading
        is DensityViewState.Applying -> current.reading
        is DensityViewState.Error -> current.reading
        DensityViewState.Loading -> null
    }

    private suspend fun perform(op: Op) {
        if (eligibleSerial != op.serial) return
        _state.value = when (op) {
            is Op.Load -> DensityViewState.Loading
            else -> DensityViewState.Applying(currentReadingOrNull())
        }
        val result = when (op) {
            is Op.Load -> useCase.read(op.serial)
            is Op.ApplyPreset -> useCase.applyPreset(op.serial, op.percent)
            is Op.ApplyCustom -> useCase.applyCustom(op.serial, op.dpi)
            is Op.Reset -> useCase.reset(op.serial)
        }
        if (eligibleSerial != op.serial) return
        _state.value = result.toViewState(currentReadingOrNull())
    }
}

private fun DensityResult.toViewState(previous: DensityReading?): DensityViewState = when (this) {
    is DensityResult.Success -> DensityViewState.Idle(reading)
    is DensityResult.Invalid -> DensityViewState.Error(
        previous,
        "Value must be between ${validation.minDpi} and ${validation.maxDpi} dpi",
    )
    is DensityResult.Unsupported -> DensityViewState.Error(previous, "Not supported on this device: $raw")
    is DensityResult.PermissionDenied -> DensityViewState.Error(previous, "Permission denied: $raw")
    is DensityResult.Malformed -> DensityViewState.Error(previous, "Unexpected device output: $raw")
    is DensityResult.TransportError -> DensityViewState.Error(previous, outcome.describe())
}

private fun AdbOutcome.describe(): String = when (this) {
    is AdbOutcome.Completed -> "No output"
    AdbOutcome.TimedOut -> "Command timed out"
    AdbOutcome.Cancelled -> "Command was cancelled"
    is AdbOutcome.TransportFailure -> reason
    is AdbOutcome.Unsupported -> reason
}
