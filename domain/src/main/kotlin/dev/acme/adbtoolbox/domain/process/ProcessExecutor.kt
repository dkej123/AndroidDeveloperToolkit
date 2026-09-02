package dev.acme.adbtoolbox.domain.process

import kotlinx.coroutines.flow.Flow

/**
 * The single port for running an OS process. Implemented by exactly one JVM adapter
 * (`:adapters-jvm`) — no other module may invoke raw process APIs.
 *
 * The returned [Flow] is cold: starting the process happens on collection, and cancelling the
 * collecting coroutine must terminate the process (and any process tree it owns) with no leaked
 * process, reader thread, or coroutine.
 */
interface ProcessExecutor {
    fun execute(request: ProcessRequest): Flow<ProcessEvent>
}
