package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkCommands
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HandlersState {
    data object Hidden : HandlersState
    data object Checking : HandlersState
    data class Ready(val handlers: LinkHandlers) : HandlersState
}

/**
 * Drives the "Who handles this link?" row: 500 ms after the last edit, cached by URI + package,
 * a superseded request is cancelled so its answer can never show (design/DEEP_LINK_DIALOG.md §2b).
 */
class DeepLinkHandlersController(
    private val scope: CoroutineScope,
    private val useCase: DeviceDeepLinkUseCase,
    private val serial: DeviceSerial,
) {
    private val mutable = MutableStateFlow<HandlersState>(HandlersState.Hidden)
    val state: StateFlow<HandlersState> = mutable.asStateFlow()
    private val cache = mutableMapOf<Pair<String, String>, LinkHandlers?>()
    private var pending: Job? = null

    fun request(uri: String, packageName: String) {
        pending?.cancel()
        val link = uri.trim()
        if (!DeepLinkCommands.isValidUri(link) || DeepLinkCommands.isBrowserIntentUri(link)) {
            mutable.value = HandlersState.Hidden
            return
        }
        val key = link to packageName
        if (key in cache) {
            mutable.value = cache[key]?.let(HandlersState::Ready) ?: HandlersState.Hidden
            return
        }
        mutable.value = HandlersState.Checking
        pending = scope.launch {
            delay(DEBOUNCE_MILLIS)
            val handlers = try {
                useCase.handlers(serial, link, packageName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            ensureActive()
            cache[key] = handlers
            mutable.value = handlers?.let(HandlersState::Ready) ?: HandlersState.Hidden
        }
    }

    /** Apps may have been installed meanwhile (dialog regained focus): forget every answer. */
    fun refresh() {
        cache.clear()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
