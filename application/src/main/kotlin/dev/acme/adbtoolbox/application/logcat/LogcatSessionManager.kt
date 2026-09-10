package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatAppendResult
import dev.acme.adbtoolbox.domain.logcat.LogcatBuffer
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferCursor
import dev.acme.adbtoolbox.domain.logcat.LogcatBufferPublication
import dev.acme.adbtoolbox.domain.logcat.LogcatCommand
import dev.acme.adbtoolbox.domain.logcat.LogcatEntry
import dev.acme.adbtoolbox.domain.logcat.LogcatEntryAssembler
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionError
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionState
import dev.acme.adbtoolbox.domain.logcat.LogcatStartOutcome
import dev.acme.adbtoolbox.domain.logcat.LogcatStopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the selected device's single Logcat stream and bounded local history (task 034). */
class LogcatSessionManager(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val transport: AdbTransport,
    val buffer: LogcatBuffer = LogcatBuffer(),
) {
    private val lifecycleMutex = Mutex()
    private val publicationMutex = Mutex()
    private val mutableState = MutableStateFlow<LogcatSessionState>(LogcatSessionState.Idle)
    val state: StateFlow<LogcatSessionState> = mutableState.asStateFlow()

    private val mutablePublication = MutableStateFlow(LogcatBufferPublication(LogcatBufferCursor(0, 0), 0, 0))
    val publication: StateFlow<LogcatBufferPublication> = mutablePublication.asStateFlow()

    private var activeSerial: DeviceSerial? = null
    private var sessionJob: Job? = null
    private var generation = 0L

    init {
        scope.launch(dispatchers.default) {
            selectedDeviceState
                .map { state -> (state.toCommandContext() as? DeviceCommandContext.Eligible)?.serial }
                .distinctUntilChanged()
                .collect { serial ->
                    if (serial == null) {
                        replaceSession(null, LogcatStopReason.DeviceUnavailable)
                    } else {
                        replaceSession(serial, LogcatStopReason.DeviceChanged)
                    }
                }
        }
    }

    suspend fun start(): LogcatStartOutcome {
        val serial = (selectedDeviceState.value.toCommandContext() as? DeviceCommandContext.Eligible)?.serial
            ?: return LogcatStartOutcome.NoEligibleDevice
        val alreadyActive = lifecycleMutex.withLock {
            activeSerial == serial && sessionJob?.isActive == true
        }
        if (alreadyActive) return LogcatStartOutcome.AlreadyActive

        replaceSession(serial, LogcatStopReason.DeviceChanged)
        return LogcatStartOutcome.Started
    }

    suspend fun stop() {
        replaceSession(null, LogcatStopReason.Requested)
    }

    suspend fun clear() {
        publicationMutex.withLock {
            val cleared = buffer.clear()
            mutablePublication.value = LogcatBufferPublication(
                cursor = cleared.cursor,
                entryCount = 0,
                totalBytes = 0,
                rejectedOversizeEntries = mutablePublication.value.rejectedOversizeEntries,
            )
        }
    }

    private suspend fun replaceSession(nextSerial: DeviceSerial?, stopReason: LogcatStopReason) {
        var oldJob: Job? = null
        var oldSerial: DeviceSerial? = null
        var replacementGeneration = 0L
        var shouldStart = false

        lifecycleMutex.withLock {
            if (nextSerial != null && activeSerial == nextSerial && sessionJob?.isActive == true) return
            if (nextSerial == null && sessionJob?.isActive != true) return

            oldJob = sessionJob
            oldSerial = activeSerial
            generation++
            replacementGeneration = generation
            sessionJob = null
            activeSerial = null
            if (oldJob?.isActive == true && oldSerial != null) {
                mutableState.value = LogcatSessionState.Stopping(oldSerial!!)
            }
            shouldStart = nextSerial != null
        }

        oldJob?.cancelAndJoin()

        lifecycleMutex.withLock {
            if (generation != replacementGeneration) return
            if (!shouldStart) {
                mutableState.value = LogcatSessionState.Stopped(oldSerial, stopReason)
                return
            }

            val serial = nextSerial ?: return
            activeSerial = serial
            mutableState.value = LogcatSessionState.Starting(serial)
            sessionJob = scope.launch(dispatchers.io) { runSession(serial, replacementGeneration) }
        }
    }

    private suspend fun runSession(serial: DeviceSerial, sessionGeneration: Long) {
        val assembler = LogcatEntryAssembler()
        val diagnostics = BoundedDiagnostics()
        var sawOutput = false
        var terminalOutcome: AdbOutcome? = null
        try {
            transport.executeStream(LogcatCommand.request(serial)).collect { event ->
                when (event) {
                    is AdbStreamEvent.Line -> {
                        sawOutput = true
                        updateStateIfCurrent(sessionGeneration, LogcatSessionState.Running(serial))
                        appendAll(assembler.accept(event.text))
                    }

                    is AdbStreamEvent.StderrLine -> diagnostics.append(event.text)
                    is AdbStreamEvent.Completed -> terminalOutcome = event.outcome
                }
            }
            appendAll(assembler.finish())
            val endState = terminalOutcome?.let { resolveTerminalState(serial, it, sawOutput, diagnostics.value()) }
                ?: LogcatSessionState.Error(
                    serial,
                    LogcatSessionError.StreamFailure(
                        "logcat stream ended without a terminal event",
                        diagnostics.value(),
                    ),
                )
            updateStateIfCurrent(sessionGeneration, endState)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { appendAll(assembler.finish()) }
            throw cancelled
        } catch (failure: Throwable) {
            appendAll(assembler.finish())
            updateStateIfCurrent(
                sessionGeneration,
                LogcatSessionState.Error(
                    serial,
                    LogcatSessionError.StreamFailure(
                        failure.message ?: "logcat stream failed",
                        diagnostics.value(),
                    ),
                ),
            )
        } finally {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (generation == sessionGeneration) sessionJob = null
                }
            }
        }
    }

    private fun resolveTerminalState(
        serial: DeviceSerial,
        outcome: AdbOutcome,
        sawOutput: Boolean,
        diagnostics: String,
    ): LogcatSessionState = when (outcome) {
        is AdbOutcome.Completed -> {
            if (sawOutput) {
                LogcatSessionState.Error(serial, LogcatSessionError.UnexpectedExit(outcome.exitCode, diagnostics))
            } else {
                LogcatSessionState.Error(
                    serial,
                    LogcatSessionError.StartFailure("logcat exited before producing output (exit ${outcome.exitCode})", diagnostics),
                )
            }
        }

        AdbOutcome.Cancelled -> LogcatSessionState.Stopped(serial, LogcatStopReason.Cancelled)
        AdbOutcome.TimedOut -> LogcatSessionState.Error(serial, LogcatSessionError.TimedOut(diagnostics))
        is AdbOutcome.TransportFailure -> LogcatSessionState.Error(
            serial,
            if (sawOutput) {
                LogcatSessionError.StreamFailure(outcome.reason, diagnostics)
            } else {
                LogcatSessionError.StartFailure(outcome.reason, diagnostics)
            },
        )

        is AdbOutcome.Unsupported -> LogcatSessionState.Error(
            serial,
            LogcatSessionError.StartFailure(outcome.reason, diagnostics),
        )
    }

    private suspend fun updateStateIfCurrent(sessionGeneration: Long, state: LogcatSessionState) {
        lifecycleMutex.withLock {
            if (generation == sessionGeneration) mutableState.value = state
        }
    }

    private suspend fun appendAll(entries: List<LogcatEntry>) {
        publicationMutex.withLock {
            entries.forEach { entry ->
                when (val result = buffer.append(entry)) {
                    is LogcatAppendResult.Added -> mutablePublication.value = LogcatBufferPublication(
                        cursor = result.cursor,
                        entryCount = result.entryCount,
                        totalBytes = result.totalBytes,
                        rejectedOversizeEntries = mutablePublication.value.rejectedOversizeEntries,
                    )

                    is LogcatAppendResult.RejectedOversize -> mutablePublication.value =
                        mutablePublication.value.copy(
                            rejectedOversizeEntries = mutablePublication.value.rejectedOversizeEntries + 1,
                        )
                }
            }
        }
    }

    private class BoundedDiagnostics(private val maxChars: Int = 8 * 1024) {
        private val text = StringBuilder()

        fun append(line: String) {
            if (text.isNotEmpty()) text.append('\n')
            text.append(line)
            if (text.length > maxChars) text.delete(0, text.length - maxChars)
        }

        fun value(): String = text.toString()
    }
}
