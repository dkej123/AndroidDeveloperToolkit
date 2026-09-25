package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.RunningProcessContributor
import dev.acme.adbtoolbox.domain.devicecontext.RunningProcessInfo
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.mirroring.MirroringExitReason
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionError
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState
import dev.acme.adbtoolbox.domain.mirroring.MirroringStartOutcome
import dev.acme.adbtoolbox.domain.mirroring.buildScrcpyArguments
import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessOutputKind
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns one cancellable scrcpy mirroring session per explicit [DeviceSerial] (task 017),
 * independent of any UI (task 018 presents it). Built directly on task 002's [ProcessExecutor] and
 * task 004's [ToolLocator] ports — never a raw process spawn, and never a second, ad hoc tool
 * resolution/argument-building path (ADR 0005: scrcpy is always-binary, never ddmlib).
 *
 * Each serial gets its own [SessionEntry] with its own child [Job] under [scope] (ADR 0004's
 * feature-local-child-scope seam, matching
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel]): starting a session for one
 * serial never touches another serial's job or state, so a globally selected device changing
 * elsewhere cannot disturb an already-running session for a different serial. Cancelling [scope]
 * (feature/project disposal) cancels every owned job, which — via
 * [dev.acme.adbtoolbox.adapters.jvm.process.JvmProcessExecutor]'s cancellation-triggered process-tree
 * teardown — leaves no orphan `scrcpy` process behind without this class needing its own separate
 * `dispose()`.
 */
class MirroringSessionManager(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val toolLocator: ToolLocator,
    private val processExecutor: ProcessExecutor,
) : RunningProcessContributor {

    private class SessionEntry(initial: MirroringSessionState) {
        val state = MutableStateFlow(initial)
        var job: Job? = null
        var stopRequested = false
    }

    private val sessions = mutableMapOf<DeviceSerial, SessionEntry>()

    /** The current session state for [serial], created (as [MirroringSessionState.Idle]) on first
     * access — always the same [StateFlow] instance for a given serial across calls. */
    fun stateFor(serial: DeviceSerial): StateFlow<MirroringSessionState> = entryFor(serial).state.asStateFlow()

    /**
     * Starts a mirroring session for [serial] with [options]. Rejected without touching any
     * existing process if a session for [serial] is already [MirroringSessionState.Starting],
     * [MirroringSessionState.Running], or [MirroringSessionState.Stopping] — at most one owned
     * session exists per serial. [MirroringSessionState.Stopping] is included even though its
     * process is already being torn down: the teardown coroutine has not yet confirmed the process
     * fully exited, so racing a second `start()` in that window would otherwise overwrite
     * [SessionEntry.job] before the prior job's cancellation completes, leaking a second owned
     * process for the same serial.
     */
    fun start(serial: DeviceSerial, options: MirroringOptions = MirroringOptions()): MirroringStartOutcome {
        val entry = entryFor(serial)
        when (entry.state.value) {
            is MirroringSessionState.Starting,
            is MirroringSessionState.Running,
            is MirroringSessionState.Stopping,
            -> return MirroringStartOutcome.Rejected

            else -> Unit
        }

        entry.stopRequested = false
        entry.state.value = MirroringSessionState.Starting(serial)
        entry.job = scope.launch(dispatchers.default) { runSession(serial, options, entry) }
        return MirroringStartOutcome.Started
    }

    /** Requests a graceful stop of [serial]'s session; a no-op if none is starting/running. */
    fun stop(serial: DeviceSerial) {
        val entry = sessions[serial] ?: return
        when (entry.state.value) {
            is MirroringSessionState.Starting, is MirroringSessionState.Running -> Unit
            else -> return
        }

        entry.stopRequested = true
        entry.state.value = MirroringSessionState.Stopping(serial)
        entry.job?.cancel()
    }

    override fun runningProcessesFor(serial: DeviceSerial?): List<RunningProcessInfo> {
        val entry = serial?.let { sessions[it] } ?: return emptyList()
        return when (entry.state.value) {
            is MirroringSessionState.Starting, is MirroringSessionState.Running, is MirroringSessionState.Stopping ->
                listOf(RunningProcessInfo(id = "scrcpy", description = "Mirroring"))

            else -> emptyList()
        }
    }

    private fun entryFor(serial: DeviceSerial): SessionEntry =
        sessions.getOrPut(serial) { SessionEntry(MirroringSessionState.Idle(serial)) }

    private suspend fun runSession(serial: DeviceSerial, options: MirroringOptions, entry: SessionEntry) {
        try {
            when (val outcome = toolLocator.locate(ToolId.Scrcpy)) {
                is DiscoveryOutcome.Failed -> {
                    entry.state.value = MirroringSessionState.Error(serial, MirroringSessionError.ToolUnavailable(outcome.error))
                    return
                }

                is DiscoveryOutcome.Found -> {
                    val tool = outcome.tool
                    // scrcpy runs `adb` itself, from $ADB or PATH. An IDE launched from the desktop
                    // usually lacks the shell PATH, so hand scrcpy the adb this plugin resolved —
                    // also keeping both on the same adb server/version.
                    val adb = (toolLocator.locate(ToolId.Adb) as? DiscoveryOutcome.Found)?.tool?.path?.value
                    val request = ProcessRequest(
                        command = ProcessCommand(
                            executable = tool.path.value,
                            arguments = buildScrcpyArguments(serial, options),
                            environment = if (adb != null) mapOf("ADB" to adb) else emptyMap(),
                        ),
                        outputKind = ProcessOutputKind.Text,
                        timeout = null,
                    )

                    var enteredRunning = false
                    var finalOutcome: ProcessOutcome? = null
                    var firstError: String? = null
                    processExecutor.execute(request).collect { event ->
                        if (firstError == null) firstError = scrcpyErrorMessage(event)
                        when (event) {
                            is ProcessEvent.Completed -> finalOutcome = event.outcome
                            else -> if (!enteredRunning) {
                                enteredRunning = true
                                entry.state.value = MirroringSessionState.Running(serial, tool.version)
                            }
                        }
                    }

                    entry.state.value = resolveExitState(serial, finalOutcome, entry.stopRequested, firstError)
                }
            }
        } catch (cancellation: CancellationException) {
            entry.state.value = MirroringSessionState.Exited(
                serial,
                if (entry.stopRequested) MirroringExitReason.Requested else MirroringExitReason.Cancelled,
            )
            throw cancellation
        }
    }

    /** scrcpy reports failures as `ERROR: <message>` lines (on stderr, or stdout on some builds). */
    private fun scrcpyErrorMessage(event: ProcessEvent): String? {
        val line = when (event) {
            is ProcessEvent.StderrText -> event.line
            is ProcessEvent.StdoutText -> event.line
            else -> return null
        }
        return line.trim().takeIf { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()?.ifEmpty { null }
    }

    private fun resolveExitState(
        serial: DeviceSerial,
        outcome: ProcessOutcome?,
        stopRequested: Boolean,
        errorDetail: String?,
    ): MirroringSessionState {
        if (stopRequested) return MirroringSessionState.Exited(serial, MirroringExitReason.Requested)
        return when (outcome) {
            is ProcessOutcome.Completed ->
                if (outcome.exitCode == 0) {
                    MirroringSessionState.Exited(serial, MirroringExitReason.ExternalWindowExit)
                } else {
                    MirroringSessionState.Exited(serial, MirroringExitReason.ProcessExited(outcome.exitCode, errorDetail))
                }

            ProcessOutcome.TimedOut -> MirroringSessionState.Exited(serial, MirroringExitReason.Timeout)

            is ProcessOutcome.StartFailure ->
                MirroringSessionState.Error(serial, MirroringSessionError.StartFailure(outcome.reason))

            null -> MirroringSessionState.Error(
                serial,
                MirroringSessionError.StartFailure("scrcpy process ended without reporting a result"),
            )
        }
    }
}
