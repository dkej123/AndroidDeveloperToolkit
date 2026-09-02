package dev.acme.adbtoolbox.adapters.jvm.process

import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessOutputKind
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The only module allowed to hold a raw JVM process handle. Runs [ProcessRequest.command] via
 * structured `ProcessBuilder(executable, *arguments)` invocation — never a shell string — and
 * turns its stdout/stderr into a bounded, backpressured [Flow] of [ProcessEvent]s.
 *
 * Cancelling collection of the returned flow (including via `withTimeout`/scope cancellation)
 * terminates the underlying OS process (and any child processes it spawned) and joins the internal
 * stream-reading coroutines before the flow finishes closing — no process, reader thread, or
 * coroutine is left running.
 */
class JvmProcessExecutor : ProcessExecutor {

    override fun execute(request: ProcessRequest): Flow<ProcessEvent> = callbackFlow {
        val builder = ProcessBuilder(
            buildList {
                add(request.command.executable)
                addAll(request.command.arguments)
            },
        )
        request.command.workingDirectory?.let { builder.directory(File(it)) }
        if (request.command.environment.isNotEmpty()) {
            builder.environment().putAll(request.command.environment)
        }
        builder.redirectErrorStream(false)

        val process = try {
            builder.start()
        } catch (e: IOException) {
            send(ProcessEvent.Completed(ProcessOutcome.StartFailure(e.message ?: e.toString())))
            close()
            awaitClose { }
            return@callbackFlow
        }

        val decodeText = request.outputKind is ProcessOutputKind.Text

        val stdoutJob = launch(Dispatchers.IO) {
            pumpStream(
                input = process.inputStream,
                decodeText = decodeText,
                onText = { line -> send(ProcessEvent.StdoutText(line)) },
                onBytes = { chunk -> send(ProcessEvent.StdoutBytes(chunk)) },
            )
        }
        val stderrJob = launch(Dispatchers.IO) {
            pumpStream(
                input = process.errorStream,
                decodeText = decodeText,
                onText = { line -> send(ProcessEvent.StderrText(line)) },
                onBytes = { chunk -> send(ProcessEvent.StderrBytes(chunk)) },
            )
        }

        val waiterJob = launch(Dispatchers.IO) {
            val outcome = awaitOutcome(process, request.timeout)
            stdoutJob.join()
            stderrJob.join()
            send(ProcessEvent.Completed(outcome))
            close()
        }

        awaitClose {
            waiterJob.cancel()
            stdoutJob.cancel()
            stderrJob.cancel()
            destroyProcessTree(process)
        }
    }.buffer(request.outputBufferCapacity)

    private suspend fun awaitOutcome(process: Process, timeout: kotlin.time.Duration?): ProcessOutcome {
        val exitCode = if (timeout != null) {
            withTimeoutOrNull(timeout) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
        } else {
            runInterruptible(Dispatchers.IO) { process.waitFor() }
        }
        return if (exitCode == null) {
            destroyProcessTree(process)
            ProcessOutcome.TimedOut
        } else {
            ProcessOutcome.Completed(exitCode)
        }
    }

    private suspend fun CoroutineScope.pumpStream(
        input: java.io.InputStream,
        decodeText: Boolean,
        onText: suspend (String) -> Unit,
        onBytes: suspend (ByteArray) -> Unit,
    ) {
        val decoder = if (decodeText) IncrementalLineDecoder() else null
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (isActive) {
                val read = try {
                    runInterruptible(Dispatchers.IO) { stream.read(buffer) }
                } catch (e: IOException) {
                    break
                }
                if (read < 0) break
                if (read == 0) continue
                val chunk = buffer.copyOf(read)
                if (decoder != null) {
                    decoder.decode(chunk).forEach { line -> onText(line) }
                } else {
                    onBytes(chunk)
                }
            }
        }
        decoder?.finish()?.forEach { line -> onText(line) }
    }

    private fun destroyProcessTree(process: Process) {
        if (!process.isAlive) return
        val handle = process.toHandle()
        handle.descendants().forEach { it.destroy() }
        process.destroy()
        process.waitFor(200, TimeUnit.MILLISECONDS)
        if (process.isAlive) {
            handle.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
