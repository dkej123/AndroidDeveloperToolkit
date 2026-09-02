package dev.acme.adbtoolbox.domain.process

import kotlin.time.Duration

/**
 * A request to run one [command] to completion (or until [timeout]/cancellation), observing its
 * output as [outputKind]-shaped events.
 *
 * [outputBufferCapacity] bounds how many undelivered [ProcessEvent]s the executor may queue before
 * the underlying stream-reading pump suspends — this is the backpressure bound, not a memory-
 * unbounded buffer.
 */
data class ProcessRequest(
    val command: ProcessCommand,
    val outputKind: ProcessOutputKind = ProcessOutputKind.Text,
    val timeout: Duration? = null,
    val outputBufferCapacity: Int = 64,
)
