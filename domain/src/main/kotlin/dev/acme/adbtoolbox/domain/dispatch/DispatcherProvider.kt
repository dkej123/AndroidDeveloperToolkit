package dev.acme.adbtoolbox.domain.dispatch

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher injection seam (ADR 0004): every ViewModel/use case that launches coroutines takes a
 * [DispatcherProvider] instead of referencing `Dispatchers.IO`/`Dispatchers.Default`/a platform main
 * dispatcher directly, so tests substitute deterministic test dispatchers and production wiring
 * (`:intellij`, task 007) substitutes real IO/default/EDT-backed dispatchers. [main] is always the
 * UI-thread context of whatever frontend composes this — `:application` code must not assume it is
 * EDT specifically, only that results observed through it are safe to touch UI state from.
 *
 * [main] is a [CoroutineContext] rather than a plain [CoroutineDispatcher]: the IntelliJ Platform's
 * own EDT dispatcher (`Dispatchers.EDT`) is itself a context bundling a dispatcher with a
 * `ModalityState` element, and callers should not have to unpack that to use `withContext(main)`.
 */
interface DispatcherProvider {
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val main: CoroutineContext
}
