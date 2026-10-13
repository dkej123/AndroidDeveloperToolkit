package io.github.dkej123.devicecockpit.application.recording

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.capture.CaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.CaptureTarget
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolLocator
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.github.dkej123.devicecockpit.domain.process.ProcessCommand
import io.github.dkej123.devicecockpit.domain.process.ProcessEvent
import io.github.dkej123.devicecockpit.domain.process.ProcessExecutor
import io.github.dkej123.devicecockpit.domain.process.ProcessOutcome
import io.github.dkej123.devicecockpit.domain.process.ProcessOutputKind
import io.github.dkej123.devicecockpit.domain.process.ProcessRequest
import io.github.dkej123.devicecockpit.domain.recording.RecordingCommands
import io.github.dkej123.devicecockpit.domain.recording.RecordingSessionError
import io.github.dkej123.devicecockpit.domain.recording.RecordingSessionState
import io.github.dkej123.devicecockpit.domain.recording.RecordingStartOutcome
import io.github.dkej123.devicecockpit.domain.recording.ScrcpyRecording
import io.github.dkej123.devicecockpit.domain.time.MonotonicClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** Bounded request/response defaults (ADR 0005) for the pull/cleanup steps that follow a stopped
 * recording; the recording itself is deliberately unbounded ([AdbTransport.executeStream] with no
 * timeout) since only a graceful [RecordingSessionManager.stop] or ADB's own maximum-duration
 * auto-completion should end it. */
val DEFAULT_RECORDING_PULL_TIMEOUT: Duration = 30.seconds
val DEFAULT_RECORDING_CLEANUP_TIMEOUT: Duration = 10.seconds

/** How long scrcpy gets after SIGTERM to write the MP4's index before it is killed. */
val SCRCPY_RECORDING_STOP_GRACE_PERIOD: Duration = 10.seconds

/**
 * Owns one cancellable remote `screenrecord` session per explicit [DeviceSerial] (task 020),
 * independent of any UI, mirroring task 017's [io.github.dkej123.devicecockpit.application.mirroring.MirroringSessionManager]
 * per-serial-ownership shape but built on the [AdbTransport] gateway (ADR 0005) rather than a local
 * [io.github.dkej123.devicecockpit.domain.process.ProcessExecutor]: `screenrecord` runs on the *device*, not this
 * host.
 *
 * The lifecycle is: [start] launches `adb shell screenrecord <remotePath>`
 * ([AdbTransport.executeStream]) and immediately marks the session [RecordingSessionState.Recording],
 * timestamped by [monotonicClock] (never a wall clock, so elapsed time survives clock adjustments).
 * That stream ends one of two ways — an explicit [stop] cancels the collecting job (the "SIGINT the
 * process" graceful stop `design/IMPLEMENTATION.md` §4 describes: cancelling
 * [AdbTransport.executeStream]'s collection tears down the underlying remote process per its own
 * contract), or the stream completes on its own once ADB's own maximum-duration cap ends
 * `screenrecord` first. Either way, this always then pulls the recorded file via a literal `cat`
 * argv over [AdbTransport.executeBinary]'s `exec-out` binary-safe channel (reusing task 019's
 * `screencap -p` transport shape rather than a separate sync-protocol pull port), commits it through
 * the shared [captureDestination]/[fileNamePolicy] ports task 019 established, and — only once that
 * local copy is confirmed committed — issues a best-effort remote `rm -f` cleanup. A pull failure
 * after a successful stop is reported as [RecordingSessionError.PullFailure] rather than a false
 * [RecordingSessionState.Saved] (task 020's explicit partial-failure requirement), and the remote
 * file is deliberately left in place in that case: deleting the only remaining copy of a recording
 * that was never actually retrieved would turn a recoverable failure into an unrecoverable one.
 *
 * Each serial gets its own [SessionEntry] with its own child [Job] under [scope] (ADR 0004's
 * feature-local-child-scope seam): starting a session for one serial never touches another serial's
 * job or state. Cancelling [scope] (feature/project disposal) cancels every owned job, which tears
 * down any in-flight remote `screenrecord` stream via the same [AdbTransport.executeStream]
 * cancellation contract — no explicit `dispose()` is needed for that guarantee.
 *
 * When [toolLocator] and [processExecutor] are given and a recent enough scrcpy is found
 * ([ScrcpyRecording.supports]), the recording runs on the host through scrcpy instead, written
 * straight into the capture target's [CaptureTarget.writablePath]: `screenrecord` stops after 180 s,
 * scrcpy does not. Stopping sends scrcpy SIGTERM and waits [SCRCPY_RECORDING_STOP_GRACE_PERIOD] for
 * it to finish the file.
 */
class RecordingSessionManager(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val adbTransport: AdbTransport,
    private val captureDestination: CaptureDestination,
    private val fileNamePolicy: FileNamePolicy,
    private val monotonicClock: MonotonicClock,
    private val clock: Clock = Clock.System,
    private val pullTimeout: Duration = DEFAULT_RECORDING_PULL_TIMEOUT,
    private val cleanupTimeout: Duration = DEFAULT_RECORDING_CLEANUP_TIMEOUT,
    private val toolLocator: ToolLocator? = null,
    private val processExecutor: ProcessExecutor? = null,
) {

    private class SessionEntry(initial: RecordingSessionState) {
        val state = MutableStateFlow(initial)
        var job: Job? = null
    }

    private val sessions = mutableMapOf<DeviceSerial, SessionEntry>()

    /** The current session state for [serial], created (as [RecordingSessionState.Idle]) on first
     * access — always the same [StateFlow] instance for a given serial across calls. */
    fun stateFor(serial: DeviceSerial): StateFlow<RecordingSessionState> = entryFor(serial).state.asStateFlow()

    /**
     * Starts a recording session for [serial]. Rejected without touching any existing remote process
     * if a session for [serial] is already [RecordingSessionState.Starting],
     * [RecordingSessionState.Recording], [RecordingSessionState.Stopping], or
     * [RecordingSessionState.Pulling] — at most one owned session exists per serial at a time.
     * [RecordingSessionState.Stopping]/[RecordingSessionState.Pulling] are included even though the
     * remote process is already being torn down/retrieved: that coroutine has not yet reached a
     * terminal state, so racing a second `start()` in that window would otherwise overwrite
     * [SessionEntry.job] before the prior job's teardown/pull completes.
     */
    fun start(serial: DeviceSerial): RecordingStartOutcome {
        val entry = entryFor(serial)
        when (entry.state.value) {
            is RecordingSessionState.Starting,
            is RecordingSessionState.Recording,
            is RecordingSessionState.Stopping,
            is RecordingSessionState.Pulling,
            -> return RecordingStartOutcome.Rejected

            else -> Unit
        }

        entry.state.value = RecordingSessionState.Starting(serial)
        entry.job = scope.launch(dispatchers.default) { runSession(serial, entry) }
        return RecordingStartOutcome.Started
    }

    /** Requests a graceful stop of [serial]'s session; a no-op if none is starting/recording. */
    fun stop(serial: DeviceSerial) {
        val entry = sessions[serial] ?: return
        when (entry.state.value) {
            is RecordingSessionState.Starting, is RecordingSessionState.Recording -> Unit
            else -> return
        }

        entry.state.value = RecordingSessionState.Stopping(serial)
        entry.job?.cancel()
    }

    private fun entryFor(serial: DeviceSerial): SessionEntry =
        sessions.getOrPut(serial) { SessionEntry(RecordingSessionState.Idle(serial)) }

    private suspend fun runSession(serial: DeviceSerial, entry: SessionEntry) {
        val baseFileName = fileNamePolicy.baseFileName(clock.now())
        val scrcpy = try {
            locateScrcpy()
        } catch (cancellation: CancellationException) {
            entry.state.value = RecordingSessionState.Idle(serial)
            throw cancellation
        }
        if (scrcpy != null && processExecutor != null) {
            val target = captureDestination.beginCapture(baseFileName)
            val path = target.writablePath
            if (path != null) return runScrcpySession(serial, entry, scrcpy, processExecutor, target, path)
            target.discard()
        }

        val remotePath = RecordingCommands.remotePath(baseFileName)
        try {
            entry.state.value = RecordingSessionState.Recording(serial, monotonicClock.markNow())
            val request = AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Shell(RecordingCommands.startRecording(remotePath)),
                timeout = null,
            )
            adbTransport.executeStream(request).collect { /* no per-line handling; only the terminal
                event (this stream ending, one way or another) drives the next transition. */ }

            // The remote process ended on its own — ADB's own maximum-duration auto-completion, or a
            // clean device-side exit — never a requested stop (that path is the CancellationException
            // branch below).
            entry.state.value = RecordingSessionState.Stopping(serial)
            pullAndCleanup(serial, entry, remotePath)
        } catch (cancellation: CancellationException) {
            // stop() already set Stopping synchronously; pulling/cleanup must still run even though
            // this coroutine's own job is now cancelled (mirrors
            // io.github.dkej123.devicecockpit.application.capture.CaptureScreenshotUseCase's NonCancellable
            // cleanup-on-cancellation seam) — otherwise a graceful stop could never reach Saved/Error.
            withContext(NonCancellable) { pullAndCleanup(serial, entry, remotePath) }
            throw cancellation
        }
    }

    private class ScrcpyTool(val executable: String, val adb: String?)

    private suspend fun locateScrcpy(): ScrcpyTool? {
        val locator = toolLocator ?: return null
        val tool = (locator.locate(ToolId.Scrcpy) as? DiscoveryOutcome.Found)?.tool ?: return null
        if (!ScrcpyRecording.supports(tool.version)) return null
        // scrcpy runs `adb` itself; hand it the adb this plugin resolved (see MirroringSessionManager).
        val adb = (locator.locate(ToolId.Adb) as? DiscoveryOutcome.Found)?.tool?.path?.value
        return ScrcpyTool(tool.path.value, adb)
    }

    private suspend fun runScrcpySession(
        serial: DeviceSerial,
        entry: SessionEntry,
        scrcpy: ScrcpyTool,
        executor: ProcessExecutor,
        target: CaptureTarget,
        outputPath: String,
    ) {
        val request = ProcessRequest(
            command = ProcessCommand(
                executable = scrcpy.executable,
                arguments = ScrcpyRecording.arguments(serial, outputPath),
                environment = scrcpy.adb?.let { mapOf("ADB" to it) } ?: emptyMap(),
            ),
            outputKind = ProcessOutputKind.Text,
            timeout = null,
            stopGracePeriod = SCRCPY_RECORDING_STOP_GRACE_PERIOD,
        )
        var outcome: ProcessOutcome? = null
        var firstError: String? = null
        try {
            entry.state.value = RecordingSessionState.Recording(serial, monotonicClock.markNow())
            executor.execute(request).collect { event ->
                if (firstError == null) firstError = scrcpyErrorMessage(event)
                if (event is ProcessEvent.Completed) outcome = event.outcome
            }
            entry.state.value = RecordingSessionState.Stopping(serial)
            finishScrcpyRecording(serial, entry, target, outcome, firstError)
        } catch (cancellation: CancellationException) {
            // The executor has already stopped scrcpy (with its grace period) by the time this runs.
            withContext(NonCancellable) { finishScrcpyRecording(serial, entry, target, outcome, firstError) }
            throw cancellation
        }
    }

    private suspend fun finishScrcpyRecording(
        serial: DeviceSerial,
        entry: SessionEntry,
        target: CaptureTarget,
        outcome: ProcessOutcome?,
        firstError: String?,
    ) {
        if (target.size() > 0L) {
            entry.state.value = RecordingSessionState.Saved(serial, target.commit())
            return
        }
        target.discard()
        val reason = firstError?.let { "scrcpy: $it" } ?: when (outcome) {
            is ProcessOutcome.StartFailure -> "scrcpy could not start: ${outcome.reason}"
            is ProcessOutcome.Completed -> "scrcpy exited with code ${outcome.exitCode} before recording anything"
            else -> "scrcpy stopped before recording anything"
        }
        entry.state.value = RecordingSessionState.Error(serial, RecordingSessionError.StartFailure(reason))
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

    private suspend fun pullAndCleanup(serial: DeviceSerial, entry: SessionEntry, remotePath: String) {
        entry.state.value = RecordingSessionState.Pulling(serial)
        val baseFileName = remotePath.substringAfterLast('/')
        val target = captureDestination.beginCapture(baseFileName)
        var bytesWritten = 0L
        val countingSink = ByteSink { chunk ->
            bytesWritten += chunk.size
            target.sink.write(chunk)
        }
        val pullOutcome = adbTransport.executeBinary(
            AdbDeviceRequest(
                serial = serial,
                operation = AdbOperation.Exec(RecordingCommands.pull(remotePath)),
                timeout = pullTimeout,
            ),
            countingSink,
        )

        val pullSucceeded = pullOutcome is AdbOutcome.Completed &&
            pullOutcome.exitCode.let { it == null || it == 0 } &&
            bytesWritten > 0L

        if (pullSucceeded) {
            val location = target.commit()
            entry.state.value = RecordingSessionState.Saved(serial, location)
            cleanupRemote(serial, remotePath)
        } else {
            target.discard()
            entry.state.value = RecordingSessionState.Error(
                serial,
                RecordingSessionError.PullFailure(
                    reason = describePullFailure(pullOutcome, bytesWritten),
                    remoteFileRemaining = pullOutcome is AdbOutcome.Completed,
                ),
            )
            // Deliberately no remote cleanup here: the local copy was never confirmed, so the remote
            // file is the only surviving copy — "safe remnant cleanup" means never deleting the one
            // copy that still exists, not always deleting on every code path.
        }
    }

    private suspend fun cleanupRemote(serial: DeviceSerial, remotePath: String) {
        // Best-effort: a failed remote cleanup does not demote an already-committed local Saved
        // result back to an error — the local file is the truth Reveal gates on.
        runCatching {
            adbTransport.executeText(
                AdbDeviceRequest(
                    serial = serial,
                    operation = AdbOperation.Shell(RecordingCommands.cleanup(remotePath)),
                    timeout = cleanupTimeout,
                ),
            )
        }
    }

    private fun describePullFailure(outcome: AdbOutcome, bytesWritten: Long): String = when {
        outcome is AdbOutcome.Completed && bytesWritten == 0L -> "Recording pull produced no data"
        outcome is AdbOutcome.Completed -> "Recording pull exited with code ${outcome.exitCode}"
        outcome is AdbOutcome.TimedOut -> "Recording pull timed out"
        outcome is AdbOutcome.Cancelled -> "Recording pull was cancelled"
        outcome is AdbOutcome.TransportFailure -> outcome.reason
        outcome is AdbOutcome.Unsupported -> outcome.reason
        else -> "Recording pull failed"
    }
}
