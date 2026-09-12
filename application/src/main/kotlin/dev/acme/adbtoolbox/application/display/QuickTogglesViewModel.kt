package dev.acme.adbtoolbox.application.display

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.AnimationScaleCommand
import dev.acme.adbtoolbox.domain.display.AnimationScaleSetting
import dev.acme.adbtoolbox.domain.display.AnimationsReadOutcome
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.DarkThemeCommand
import dev.acme.adbtoolbox.domain.display.DisplaySettingRead
import dev.acme.adbtoolbox.domain.display.ShowTouchesCommand
import dev.acme.adbtoolbox.domain.display.combineAnimationReads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the Display view's three quick toggles (task 028): dark theme, show touches, and animations
 * off — the last of which writes all three independent Android animation-scale settings and always
 * trusts a final readback over the writes it just issued, so a partial multi-command failure never
 * gets reported as a flattened success (`design/README.md` §5, task 028's #1 acceptance criterion).
 *
 * Each toggle has its own [MutableSharedFlow] of write intents, drained by its own
 * `collectLatest` loop — the same serialization pattern
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel] uses for persistence writes: a
 * still-in-flight write superseded by a newer intent for the *same* toggle is cancelled outright
 * (never left to interleave with the new one), while the three toggles remain independent of each
 * other. [_context] is read (never awaited) after every suspend point in a write to detect a device
 * switch or a device becoming ineligible mid-flight, so a stale result is dropped rather than
 * applied to the wrong device's displayed state.
 */
class QuickTogglesViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val _state = MutableStateFlow(QuickTogglesViewState())
    val state: StateFlow<QuickTogglesViewState> = _state.asStateFlow()

    private val _context = MutableStateFlow(selectedDeviceState.value.toCommandContext())

    private val darkThemeRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val showTouchesRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val animationsRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    init {
        scope.launch(dispatchers.io) {
            selectedDeviceState.map { it.toCommandContext() }.distinctUntilChanged().collect { context ->
                _context.value = context
                if (context is DeviceCommandContext.Eligible) {
                    refreshAll(context.serial)
                } else {
                    _state.value = QuickTogglesViewState()
                }
            }
        }
        scope.launch(dispatchers.io) { darkThemeRequests.collectLatest(::applyDarkTheme) }
        scope.launch(dispatchers.io) { showTouchesRequests.collectLatest(::applyShowTouches) }
        scope.launch(dispatchers.io) { animationsRequests.collectLatest(::applyAnimationsOff) }
    }

    fun handle(intent: QuickTogglesIntent) {
        when (intent) {
            is QuickTogglesIntent.SetDarkTheme -> darkThemeRequests.tryEmit(intent.enabled)
            is QuickTogglesIntent.SetShowTouches -> showTouchesRequests.tryEmit(intent.enabled)
            is QuickTogglesIntent.SetAnimationsOff -> animationsRequests.tryEmit(intent.off)
            QuickTogglesIntent.Refresh -> {
                val serial = eligibleSerialOrNull() ?: return
                scope.launch(dispatchers.io) { refreshAll(serial) }
            }
        }
    }

    private suspend fun refreshAll(serial: DeviceSerial) {
        val darkTheme = DarkThemeCommand.parseRead(transport.executeText(DarkThemeCommand.readRequest(serial)))
        if (!isCurrentSerial(serial)) return
        _state.value = _state.value.copy(darkTheme = darkTheme.toFieldState(QuickToggleFieldState.Loading))

        val showTouches = ShowTouchesCommand.parseRead(transport.executeText(ShowTouchesCommand.readRequest(serial)))
        if (!isCurrentSerial(serial)) return
        _state.value = _state.value.copy(showTouches = showTouches.toFieldState(QuickToggleFieldState.Loading))

        val animations = readAnimations(serial)
        if (!isCurrentSerial(serial)) return
        _state.value = _state.value.copy(animations = animations.toFieldState(QuickToggleFieldState.Loading))
    }

    private suspend fun applyDarkTheme(enabled: Boolean) {
        val serial = eligibleSerialOrNull() ?: return
        val previous = _state.value.darkTheme
        _state.value = _state.value.copy(darkTheme = QuickToggleFieldState.Applying(enabled))
        transport.executeText(DarkThemeCommand.writeRequest(serial, enabled))
        val read = DarkThemeCommand.parseRead(transport.executeText(DarkThemeCommand.readRequest(serial)))
        if (!isCurrentSerial(serial)) return
        _state.value = _state.value.copy(darkTheme = read.toFieldState(previous))
    }

    private suspend fun applyShowTouches(enabled: Boolean) {
        val serial = eligibleSerialOrNull() ?: return
        val previous = _state.value.showTouches
        _state.value = _state.value.copy(showTouches = QuickToggleFieldState.Applying(enabled))
        transport.executeText(ShowTouchesCommand.writeRequest(serial, enabled))
        val read = ShowTouchesCommand.parseRead(transport.executeText(ShowTouchesCommand.readRequest(serial)))
        if (!isCurrentSerial(serial)) return
        _state.value = _state.value.copy(showTouches = read.toFieldState(previous))
    }

    /**
     * Writes all three animation-scale settings, then always re-reads all three and reports
     * whatever they actually are — never assuming the three writes all landed. A write that fails
     * or is ignored by the device simply shows up as a disagreeing value in the readback, which
     * [combineAnimationReads] reports as [AnimationsSummary.Mixed] rather than a false success.
     */
    private suspend fun applyAnimationsOff(off: Boolean) {
        val serial = eligibleSerialOrNull() ?: return
        val previous = _state.value.animations
        val optimistic = if (off) AnimationsSummary.AllOff else AnimationsSummary.AllOn
        _state.value = _state.value.copy(animations = QuickToggleFieldState.Applying(optimistic))
        val enabled = !off
        AnimationScaleSetting.entries.forEach { setting ->
            transport.executeText(AnimationScaleCommand.writeRequest(serial, setting, enabled))
        }
        if (!isCurrentSerial(serial)) return
        val outcome = readAnimations(serial)
        if (!isCurrentSerial(serial)) return
        _state.value = _state.value.copy(animations = outcome.toFieldState(previous))
    }

    private suspend fun readAnimations(serial: DeviceSerial): AnimationsReadOutcome {
        val window = AnimationScaleCommand.parseRead(
            transport.executeText(AnimationScaleCommand.readRequest(serial, AnimationScaleSetting.WINDOW)),
        )
        val transition = AnimationScaleCommand.parseRead(
            transport.executeText(AnimationScaleCommand.readRequest(serial, AnimationScaleSetting.TRANSITION)),
        )
        val animatorDuration = AnimationScaleCommand.parseRead(
            transport.executeText(AnimationScaleCommand.readRequest(serial, AnimationScaleSetting.ANIMATOR_DURATION)),
        )
        return combineAnimationReads(window, transition, animatorDuration)
    }

    private fun eligibleSerialOrNull(): DeviceSerial? = (_context.value as? DeviceCommandContext.Eligible)?.serial

    private fun isCurrentSerial(serial: DeviceSerial): Boolean = eligibleSerialOrNull() == serial
}

private fun <T> DisplaySettingRead<T>.toFieldState(previous: QuickToggleFieldState<T>): QuickToggleFieldState<T> =
    when (this) {
        is DisplaySettingRead.Value -> QuickToggleFieldState.Idle(value)
        DisplaySettingRead.NotSet ->
            QuickToggleFieldState.Error("Not supported on this device", previous.valueOrNull())
        is DisplaySettingRead.PermissionDenied -> QuickToggleFieldState.Error(message, previous.valueOrNull())
        is DisplaySettingRead.Malformed ->
            QuickToggleFieldState.Error("Unexpected device output: $raw", previous.valueOrNull())
        is DisplaySettingRead.TransportFailed ->
            QuickToggleFieldState.Error(outcome.describe(), previous.valueOrNull())
    }

private fun AnimationsReadOutcome.toFieldState(
    previous: QuickToggleFieldState<AnimationsSummary>,
): QuickToggleFieldState<AnimationsSummary> = when (this) {
    is AnimationsReadOutcome.Ready -> QuickToggleFieldState.Idle(summary)
    is AnimationsReadOutcome.Unsupported -> QuickToggleFieldState.Error(
        "Not supported on this device: ${settings.joinToString { it.settingName }}",
        previous.valueOrNull(),
    )
    is AnimationsReadOutcome.PermissionDenied -> QuickToggleFieldState.Error(message, previous.valueOrNull())
    is AnimationsReadOutcome.Malformed ->
        QuickToggleFieldState.Error("Unexpected device output: $raw", previous.valueOrNull())
    is AnimationsReadOutcome.TransportFailed ->
        QuickToggleFieldState.Error(outcome.describe(), previous.valueOrNull())
}

private fun AdbOutcome.describe(): String = when (this) {
    is AdbOutcome.Completed -> "No output"
    AdbOutcome.TimedOut -> "Timed out"
    AdbOutcome.Cancelled -> "Cancelled"
    is AdbOutcome.TransportFailure -> reason
    is AdbOutcome.Unsupported -> reason
}
