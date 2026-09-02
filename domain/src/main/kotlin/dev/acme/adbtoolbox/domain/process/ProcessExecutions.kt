package dev.acme.adbtoolbox.domain.process

import kotlinx.coroutines.flow.collect

/**
 * Runs [request] and collects its [ProcessOutputKind.Text] events into a bounded [ProcessResult].
 * Stdout and stderr are truncated independently once either exceeds [maxBytesPerStream] — the
 * process is still left to run to completion, but further bytes on the truncated stream are
 * discarded rather than buffered.
 */
suspend fun ProcessExecutor.executeBuffered(
    request: ProcessRequest,
    maxBytesPerStream: Int = 1 shl 20,
): ProcessResult {
    require(request.outputKind is ProcessOutputKind.Text) {
        "executeBuffered requires ProcessOutputKind.Text, got ${request.outputKind}"
    }

    val stdout = StringBuilder()
    val stderr = StringBuilder()
    var stdoutBytes = 0
    var stderrBytes = 0
    var stdoutTruncated = false
    var stderrTruncated = false
    var outcome: ProcessOutcome = ProcessOutcome.StartFailure("executor completed without a Completed event")

    execute(request).collect { event ->
        when (event) {
            is ProcessEvent.StdoutText -> {
                if (!stdoutTruncated) {
                    val lineSize = event.line.encodeToByteArray().size + 1
                    if (stdoutBytes + lineSize > maxBytesPerStream) {
                        stdoutTruncated = true
                    } else {
                        stdout.append(event.line).append('\n')
                        stdoutBytes += lineSize
                    }
                }
            }

            is ProcessEvent.StderrText -> {
                if (!stderrTruncated) {
                    val lineSize = event.line.encodeToByteArray().size + 1
                    if (stderrBytes + lineSize > maxBytesPerStream) {
                        stderrTruncated = true
                    } else {
                        stderr.append(event.line).append('\n')
                        stderrBytes += lineSize
                    }
                }
            }

            is ProcessEvent.StdoutBytes, is ProcessEvent.StderrBytes ->
                error("received binary output event for a Text request: $event")

            is ProcessEvent.Completed -> outcome = event.outcome
        }
    }

    return ProcessResult(
        outcome = outcome,
        stdout = stdout.toString(),
        stderr = stderr.toString(),
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
    )
}

/**
 * Runs [request] and streams its [ProcessOutputKind.Binary] stdout bytes into [stdoutSink] as they
 * arrive, without buffering the whole output in memory. Stderr bytes are observed but not sunk —
 * callers needing stderr bytes too should collect [ProcessExecutor.execute] directly.
 */
suspend fun ProcessExecutor.executeToSink(
    request: ProcessRequest,
    stdoutSink: ByteSink,
): ProcessOutcome {
    require(request.outputKind is ProcessOutputKind.Binary) {
        "executeToSink requires ProcessOutputKind.Binary, got ${request.outputKind}"
    }

    var outcome: ProcessOutcome = ProcessOutcome.StartFailure("executor completed without a Completed event")

    execute(request).collect { event ->
        when (event) {
            is ProcessEvent.StdoutBytes -> stdoutSink.write(event.bytes)
            is ProcessEvent.StderrBytes -> Unit
            is ProcessEvent.StdoutText, is ProcessEvent.StderrText ->
                error("received text output event for a Binary request: $event")

            is ProcessEvent.Completed -> outcome = event.outcome
        }
    }

    return outcome
}
