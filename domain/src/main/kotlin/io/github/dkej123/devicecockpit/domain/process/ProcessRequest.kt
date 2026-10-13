package io.github.dkej123.devicecockpit.domain.process

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A request to run one [command] to completion (or until [timeout]/cancellation), observing its
 * output as [outputKind]-shaped events.
 *
 * [outputBufferCapacity] bounds how many undelivered [ProcessEvent]s the executor may queue before
 * the underlying stream-reading pump suspends — this is the backpressure bound, not a memory-
 * unbounded buffer.
 *
 * [stopGracePeriod] is how long a cancelled process gets to exit after SIGTERM before it is killed —
 * longer for a process that must finish a file on the way out (scrcpy `--record`).
 */
data class ProcessRequest(
    val command: ProcessCommand,
    val outputKind: ProcessOutputKind = ProcessOutputKind.Text,
    val timeout: Duration? = null,
    val outputBufferCapacity: Int = 64,
    val stopGracePeriod: Duration = DEFAULT_STOP_GRACE_PERIOD,
)

val DEFAULT_STOP_GRACE_PERIOD: Duration = 200.milliseconds
