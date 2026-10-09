package io.github.dkej123.devicecockpit.intellij.ui.common

import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * Accepts coroutines but never runs them. The views' "click -> real view model" tests assert the
 * synchronous state a click sets (Starting, isCapturing, busyAction); on a fast runner the work the
 * view model launches on a real dispatcher finished first and moved that state on (CI 2026-10-05).
 */
object HeldDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

/** A [DispatcherProvider] whose coroutines never run, see [HeldDispatcher]. */
object HeldDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher = HeldDispatcher
    override val io: CoroutineDispatcher = HeldDispatcher
    override val main: CoroutineContext = HeldDispatcher
}
