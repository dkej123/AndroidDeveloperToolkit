package dev.acme.adbtoolbox.application.diagnostics

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.time.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Records every [AdbTransport] call under the transport's [name] (`ddmlib`, `binary`, `fallback`),
 * so an exported log shows which transport actually served each request, every `Unsupported` →
 * fallback hop and its reason, the outcome and the duration. Results pass through unchanged.
 * [stats], when given, aggregates timings for transports that do not run an external process
 * (ddmlib); the binary transport is already counted by [LoggingProcessExecutor].
 */
class LoggingAdbTransport(
    private val delegate: AdbTransport,
    private val name: String,
    private val log: DiagnosticsLog,
    private val clock: MonotonicClock,
    private val stats: CommandStatsCollector? = null,
) : AdbTransport {

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val start = clock.markNow()
        val result = logCancellation(request, start) { delegate.executeText(request) }
        report(
            request,
            result.outcome,
            start,
            mapOf("stdoutBytes" to result.stdout.length.toLong(), "stderr" to result.stderr.trim().takeLast(500).ifEmpty { null }),
        )
        return result
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flow {
        val start = clock.markNow()
        log.log(DiagLevel.DEBUG, DiagCategory.ADB, "stream started", fields(request))
        var lines = 0L
        var outcome: AdbOutcome? = null
        logCancellation(request, start, extra = { mapOf("lines" to lines) }) {
            delegate.executeStream(request).collect { event ->
                when (event) {
                    is AdbStreamEvent.Line -> lines++
                    is AdbStreamEvent.StderrLine -> Unit
                    is AdbStreamEvent.Completed -> outcome = event.outcome
                }
                emit(event)
            }
        }
        report(request, outcome ?: AdbOutcome.TransportFailure("stream ended without a terminal event"), start, mapOf("lines" to lines))
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome {
        val start = clock.markNow()
        var bytes = 0L
        val outcome = logCancellation(request, start, extra = { mapOf("bytes" to bytes) }) {
            delegate.executeBinary(request) { chunk ->
                bytes += chunk.size
                sink.write(chunk)
            }
        }
        report(request, outcome, start, mapOf("bytes" to bytes))
        return outcome
    }

    private inline fun <T> logCancellation(
        request: AdbRequest,
        start: kotlin.time.Duration,
        extra: () -> Map<String, Any?> = { emptyMap() },
        block: () -> T,
    ): T = try {
        block()
    } catch (cancellation: CancellationException) {
        log.log(DiagLevel.DEBUG, DiagCategory.ADB, "cancelled", fields(request) + extra() + ("ms" to elapsedMs(start)))
        throw cancellation
    }

    private fun report(request: AdbRequest, outcome: AdbOutcome, start: kotlin.time.Duration, extra: Map<String, Any?>) {
        val ms = elapsedMs(start)
        val base = fields(request) + extra.filterValues { it != null } + mapOf("outcome" to outcome.toString(), "ms" to ms)
        when (outcome) {
            is AdbOutcome.Unsupported ->
                log.log(DiagLevel.INFO, DiagCategory.ADB, "unsupported, falling back", base + ("reason" to outcome.reason))

            else -> {
                val failed = !(outcome is AdbOutcome.Completed && (outcome.exitCode == null || outcome.exitCode == 0))
                val level = when {
                    failed || ms > SlowThresholds.WARN_MS -> DiagLevel.WARN
                    ms > SlowThresholds.INFO_MS -> DiagLevel.INFO
                    else -> DiagLevel.DEBUG
                }
                stats?.record("$name ${describeShape(request)}", ms, failed)
                log.log(level, DiagCategory.ADB, if (failed) "failed" else "done", base)
            }
        }
    }

    private fun fields(request: AdbRequest): Map<String, Any?> = mapOf(
        "transport" to name,
        "request" to describe(request),
        "timeout" to request.timeout,
    )

    private fun elapsedMs(start: kotlin.time.Duration): Long = (clock.markNow() - start).inWholeMilliseconds

    private fun describeShape(request: AdbRequest): String = describe(request)
        .split(' ')
        .let { words -> if (request is AdbDeviceRequest) words.drop(1) else words }
        .take(3)
        .joinToString(" ")

    private fun describe(request: AdbRequest): String = when (request) {
        is AdbServerRequest -> "server " + request.arguments.joinToString(" ")
        is AdbDeviceRequest -> "${request.serial} " + when (val operation = request.operation) {
            is AdbOperation.Shell -> "shell " + operation.command.render()
            is AdbOperation.Exec -> "exec " + operation.arguments.joinToString(" ")
            is AdbOperation.Host -> "host " + operation.arguments.joinToString(" ")
        }
    }
}
