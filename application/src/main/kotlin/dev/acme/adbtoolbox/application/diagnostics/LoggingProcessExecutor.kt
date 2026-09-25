package dev.acme.adbtoolbox.application.diagnostics

import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.time.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val STDERR_TAIL_LINES = 20

/**
 * Records every external process the plugin runs (binary adb, scrcpy, version probes): argv,
 * environment overrides, outcome, duration and output sizes. Routine fast successes are DEBUG
 * (and still counted in [stats]); slow or failed runs are WARN with the tail of stderr. Events
 * pass through unchanged, and cancellation is logged and rethrown.
 */
class LoggingProcessExecutor(
    private val delegate: ProcessExecutor,
    private val log: DiagnosticsLog,
    private val clock: MonotonicClock,
    private val stats: CommandStatsCollector,
) : ProcessExecutor {

    override fun execute(request: ProcessRequest): Flow<ProcessEvent> = flow {
        val command = request.command
        val shape = commandShapeKey(command.executable, command.arguments)
        val display = (listOf(shape.substringBefore(' ')) + command.arguments).joinToString(" ")
        val baseFields = buildMap<String, Any?> {
            put("command", display)
            put("argv", (listOf(command.executable) + command.arguments).joinToString(" "))
            if (command.environment.isNotEmpty()) {
                put("env", command.environment.entries.joinToString(",") { "${it.key}=${it.value}" })
            }
            put("timeout", request.timeout)
        }
        val longRunning = request.timeout == null
        log.log(if (longRunning) DiagLevel.INFO else DiagLevel.DEBUG, DiagCategory.PROCESS, "started", baseFields)

        val start = clock.markNow()
        var stdoutBytes = 0L
        var stderrBytes = 0L
        val stderrTail = ArrayDeque<String>()
        var outcome: ProcessOutcome? = null
        try {
            delegate.execute(request).collect { event ->
                when (event) {
                    is ProcessEvent.StdoutText -> stdoutBytes += event.line.encodeToByteArray().size
                    is ProcessEvent.StdoutBytes -> stdoutBytes += event.bytes.size
                    is ProcessEvent.StderrText -> {
                        stderrBytes += event.line.encodeToByteArray().size
                        stderrTail.addLast(event.line)
                        if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                    }
                    is ProcessEvent.StderrBytes -> stderrBytes += event.bytes.size
                    is ProcessEvent.Completed -> outcome = event.outcome
                }
                emit(event)
            }
        } catch (cancellation: CancellationException) {
            val ms = elapsedMs(start)
            log.log(
                if (longRunning) DiagLevel.INFO else DiagLevel.DEBUG,
                DiagCategory.PROCESS,
                "cancelled",
                baseFields + mapOf("ms" to ms, "stdoutBytes" to stdoutBytes),
            )
            stats.record(shape, ms, failed = false)
            throw cancellation
        }

        val ms = elapsedMs(start)
        val finished = outcome
        val failed = finished !is ProcessOutcome.Completed || finished.exitCode != 0
        val level = when {
            failed || ms > SlowThresholds.WARN_MS -> DiagLevel.WARN
            ms > SlowThresholds.INFO_MS || longRunning -> DiagLevel.INFO
            else -> DiagLevel.DEBUG
        }
        stats.record(shape, ms, failed)
        log.log(
            level,
            DiagCategory.PROCESS,
            "finished",
            baseFields + buildMap {
                put("outcome", finished?.toString() ?: "no terminal event")
                put("ms", ms)
                put("stdoutBytes", stdoutBytes)
                put("stderrBytes", stderrBytes)
                if (failed && stderrTail.isNotEmpty()) put("stderrTail", stderrTail.joinToString(" | "))
            },
        )
    }

    private fun elapsedMs(start: kotlin.time.Duration): Long = (clock.markNow() - start).inWholeMilliseconds
}
