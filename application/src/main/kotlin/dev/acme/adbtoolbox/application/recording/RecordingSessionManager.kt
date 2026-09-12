package dev.acme.adbtoolbox.application.recording

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.CaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.recording.RecordingCommands
import dev.acme.adbtoolbox.domain.recording.RecordingSessionError
import dev.acme.adbtoolbox.domain.recording.RecordingSessionState
import dev.acme.adbtoolbox.domain.recording.RecordingStartOutcome
import dev.acme.adbtoolbox.domain.time.MonotonicClock
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

/**
 * Owns one cancellable remote `screenrecord` session per explicit [DeviceSerial] (task 020),
 * independent of any UI, mirroring task 017's [dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager]
 * per-serial-ownership shape but built on the [AdbTransport] gateway (ADR 0005) rather than a local
 * [dev.acme.adbtoolbox.domain.process.ProcessExecutor]: `screenrecord` runs on the *device*, not this
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
            // dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase's NonCancellable
            // cleanup-on-cancellation seam) — otherwise a graceful stop could never reach Saved/Error.
            withContext(NonCancellable) { pullAndCleanup(serial, entry, remotePath) }
            throw cancellation
        }
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
