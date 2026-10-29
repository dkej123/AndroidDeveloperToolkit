package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [opened] is set when the link joined Recent; [result] drives the in-dialog banner (handoff 7). */
data class DeviceLinkLaunchState(
    val busy: Boolean = false,
    val opened: RecentDeviceLink? = null,
    val result: DeviceLinkResult? = null,
    val request: RecentDeviceLink? = null,
    val coldStart: Boolean = false,
    val atMillis: Long = 0,
)

/** Owns one launch at a time. Clear cancels it and suppresses any superseded result. */
class DeviceDeepLinkController(
    private val scope: CoroutineScope,
    private val useCase: DeviceDeepLinkUseCase,
    private val serial: DeviceSerial,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(DeviceLinkLaunchState())
    val state: StateFlow<DeviceLinkLaunchState> = mutable.asStateFlow()
    private val generation = MutableStateFlow(0L)
    private var pending: Job? = null

    /** While busy the previous result stays on screen until the new one arrives (handoff 7). */
    fun open(uri: String, pkg: String, coldStart: Boolean = false) {
        if (mutable.value.busy) return
        val token = generation.value + 1
        generation.value = token
        mutable.value = mutable.value.copy(busy = true)
        val request = RecentDeviceLink(uri, pkg)
        fun finished(result: DeviceLinkResult) = DeviceLinkLaunchState(
            opened = request.takeIf { result is DeviceLinkResult.Opened },
            result = result, request = request, coldStart = coldStart, atMillis = nowMillis(),
        )
        pending = scope.launch {
            try {
                val result = useCase.open(serial, uri, pkg, coldStart)
                if (generation.value != token) return@launch
                mutable.value = finished(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation.value == token) mutable.value = finished(DeviceLinkResult.Failed(error.message ?: "Could not open link"))
            } finally {
                if (generation.value == token && mutable.value.busy) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    /** × on the banner, or an edit of the form: the result no longer describes what is shown. */
    fun dismiss() {
        if (!mutable.value.busy) mutable.value = DeviceLinkLaunchState()
    }

    fun clear() {
        generation.value += 1
        pending?.cancel()
        pending = null
        mutable.value = DeviceLinkLaunchState()
    }
}
