package dev.acme.adbtoolbox.adapters.adb.binary

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessOutputKind
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.process.executeBuffered
import dev.acme.adbtoolbox.domain.process.executeToSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The `:adapters-adb` [AdbTransport] backed by the binary `adb` executable (ADR 0005): resolves the
 * executable via [toolLocator] (task 004) and invokes it exclusively through [processExecutor]
 * (task 002) — never a raw `ProcessBuilder`. Every device-scoped request adds exactly one
 * `-s <serial>` argument right after the executable; a server-scoped [AdbServerRequest] carries no
 * serial at all.
 *
 * Mirrors [dev.acme.adbtoolbox.adapters.adb.ddmlib.DdmlibAdbTransport]'s request-shape contract so
 * the two adapters agree on which [AdbRequest]/[AdbOperation] combinations each entry point
 * supports, which the fallback selector (ADR 0005) relies on: [executeText]/[executeStream] only
 * support [AdbOperation.Shell] (plus [AdbServerRequest]); [executeBinary] only supports
 * [AdbOperation.Exec]. An unsupported combination is reported as [AdbOutcome.Unsupported] before
 * any process is started — never a partial or ambiguous attempt — so it is always safe to retry
 * that class of failure on a different transport. Never selects/falls back to another transport
 * itself — that choice is made once, at the `:intellij` composition root (task 007), or generically
 * by [dev.acme.adbtoolbox.adapters.adb.selector.FallbackAdbTransport].
 */
class BinaryAdbTransport(
    private val toolLocator: ToolLocator,
    private val processExecutor: ProcessExecutor,
) : AdbTransport {

    override suspend fun executeText(request: AdbRequest): AdbTextResult =
        when (val resolution = resolveShellCapableCommand(request)) {
            is CommandResolution.Unsupported -> AdbTextResult(AdbOutcome.Unsupported(resolution.reason), "", "")
            is CommandResolution.Failure -> AdbTextResult(AdbOutcome.TransportFailure(resolution.reason), "", "")
            is CommandResolution.Ready -> {
                val result = processExecutor.executeBuffered(
                    ProcessRequest(
                        command = resolution.command,
                        outputKind = ProcessOutputKind.Text,
                        timeout = request.timeout,
                    ),
                )
                AdbTextResult(result.outcome.toAdbOutcome(), result.stdout, result.stderr)
            }
        }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flow {
        when (val resolution = resolveShellCapableCommand(request)) {
            is CommandResolution.Unsupported ->
                emit(AdbStreamEvent.Completed(AdbOutcome.Unsupported(resolution.reason)))

            is CommandResolution.Failure ->
                emit(AdbStreamEvent.Completed(AdbOutcome.TransportFailure(resolution.reason)))

            is CommandResolution.Ready -> {
                var outcome: AdbOutcome =
                    AdbOutcome.TransportFailure("binary adb stream completed without a terminal event")
                processExecutor.execute(
                    ProcessRequest(
                        command = resolution.command,
                        outputKind = ProcessOutputKind.Text,
                        timeout = request.timeout,
                    ),
                ).collect { event ->
                    when (event) {
                        is ProcessEvent.StdoutText -> emit(AdbStreamEvent.Line(event.line))
                        is ProcessEvent.StderrText -> emit(AdbStreamEvent.Line(event.line))
                        is ProcessEvent.StdoutBytes, is ProcessEvent.StderrBytes ->
                            error("received binary process output for a text adb stream: $event")

                        is ProcessEvent.Completed -> outcome = event.outcome.toAdbOutcome()
                    }
                }
                emit(AdbStreamEvent.Completed(outcome))
            }
        }
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome =
        when (val resolution = resolveExecCommand(request)) {
            is CommandResolution.Unsupported -> AdbOutcome.Unsupported(resolution.reason)
            is CommandResolution.Failure -> AdbOutcome.TransportFailure(resolution.reason)
            is CommandResolution.Ready -> processExecutor.executeToSink(
                ProcessRequest(
                    command = resolution.command,
                    outputKind = ProcessOutputKind.Binary,
                    timeout = request.timeout,
                ),
                sink,
            ).toAdbOutcome()
        }

    private suspend fun resolveShellCapableCommand(request: AdbRequest): CommandResolution = when (request) {
        is AdbServerRequest -> resolveCommand(request.arguments)
        is AdbDeviceRequest -> {
            val operation = request.operation
            if (operation !is AdbOperation.Shell) {
                CommandResolution.Unsupported("binary adb text/stream execution only supports shell operations")
            } else {
                resolveCommand(listOf("-s", request.serial.toString(), "shell", operation.command.render()))
            }
        }
    }

    private suspend fun resolveExecCommand(request: AdbRequest): CommandResolution {
        if (request !is AdbDeviceRequest) {
            return CommandResolution.Unsupported("binary adb binary execution requires a device-scoped request")
        }
        val operation = request.operation
        if (operation !is AdbOperation.Exec) {
            return CommandResolution.Unsupported("binary adb binary execution only supports exec operations")
        }
        return resolveCommand(listOf("-s", request.serial.toString(), "exec-out") + operation.arguments)
    }

    private suspend fun resolveCommand(arguments: List<String>): CommandResolution =
        when (val outcome = toolLocator.locate(ToolId.Adb)) {
            is DiscoveryOutcome.Found ->
                CommandResolution.Ready(ProcessCommand(executable = outcome.tool.path.value, arguments = arguments))

            is DiscoveryOutcome.Failed -> CommandResolution.Failure(outcome.error.describe())
        }

    private fun DiscoveryError.describe(): String = when (this) {
        is DiscoveryError.ToolNotFound -> "adb executable not found (tried: ${attemptedSources.joinToString()})"
        is DiscoveryError.ExecutableInvalid -> "adb executable at '$path' is not valid: $reason"
        is DiscoveryError.VersionQueryFailed -> "adb executable at '$path' failed its version check: $reason"
    }

    private fun ProcessOutcome.toAdbOutcome(): AdbOutcome = when (this) {
        is ProcessOutcome.Completed -> AdbOutcome.Completed(exitCode)
        ProcessOutcome.TimedOut -> AdbOutcome.TimedOut
        is ProcessOutcome.StartFailure -> AdbOutcome.TransportFailure(reason)
    }

    private sealed interface CommandResolution {
        data class Ready(val command: ProcessCommand) : CommandResolution
        data class Unsupported(val reason: String) : CommandResolution
        data class Failure(val reason: String) : CommandResolution
    }
}
