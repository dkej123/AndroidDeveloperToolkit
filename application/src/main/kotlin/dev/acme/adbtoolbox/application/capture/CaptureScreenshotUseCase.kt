package dev.acme.adbtoolbox.application.capture

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.CaptureDestination
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.process.ByteSink
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** `design/IMPLEMENTATION.md` §4's `adb -s $S exec-out screencap -p` argv — never shell-parsed. */
private val SCREENCAP_ARGUMENTS = listOf("screencap", "-p")

/** Bounded request/response default (ADR 0005); a screenshot is a one-shot call, not a stream. */
val DEFAULT_SCREENSHOT_TIMEOUT: Duration = 15.seconds

/** What [CaptureScreenshotUseCase.capture] produced. */
sealed interface CaptureScreenshotResult {
    data class Success(val location: CaptureLocation) : CaptureScreenshotResult
    data class Failure(val reason: String) : CaptureScreenshotResult
}

/**
 * Captures one binary-safe PNG screenshot from [serial] (task 019): `exec-out screencap -p`
 * (ADR 0005's binary-output path, [AdbTransport.executeBinary]) streamed straight into a
 * [CaptureDestination]'s target, never buffered whole in memory and never text-decoded. The target
 * is committed only for a genuinely complete, non-empty, zero-(or-unknown-)exit capture — every
 * other outcome (non-zero exit, empty output, timeout, transport failure, cancellation) discards
 * the partial target, so a failed/cancelled capture is never left behind or reported as success.
 *
 * [serial] is taken once as a parameter rather than re-read from some "current device" source
 * mid-capture, so a capture already in flight for one device is never silently reattributed to a
 * different device if the caller's selection changes while this suspends (task 019's TDD plan).
 *
 * Cleanup on the non-success path runs under [NonCancellable]: if the calling coroutine itself is
 * cancelled while [AdbTransport.executeBinary] suspends, the [CancellationException][kotlinx.coroutines.CancellationException]
 * must still reach the caller (real cancellation semantics, never swallowed), but the partial file
 * this use case wrote must still be discarded rather than left on disk — a plain `finally` block
 * alone would have its own suspend calls fail immediately once the enclosing job is already
 * cancelled.
 */
class CaptureScreenshotUseCase(
    private val adbTransport: AdbTransport,
    private val captureDestination: CaptureDestination,
    private val fileNamePolicy: FileNamePolicy,
    private val clock: Clock = Clock.System,
    private val timeout: Duration = DEFAULT_SCREENSHOT_TIMEOUT,
) {
    suspend fun capture(serial: DeviceSerial): CaptureScreenshotResult {
        val baseFileName = fileNamePolicy.baseFileName(clock.now())
        val target = captureDestination.beginCapture(baseFileName)
        var bytesWritten = 0L
        val countingSink = ByteSink { chunk ->
            bytesWritten += chunk.size
            target.sink.write(chunk)
        }
        var committed = false
        try {
            val outcome = adbTransport.executeBinary(
                AdbDeviceRequest(
                    serial = serial,
                    operation = AdbOperation.Exec(SCREENCAP_ARGUMENTS),
                    timeout = timeout,
                ),
                countingSink,
            )
            if (outcome is AdbOutcome.Completed && outcome.exitCode.let { it == null || it == 0 } && bytesWritten > 0L) {
                val location = target.commit()
                committed = true
                return CaptureScreenshotResult.Success(location)
            }
            return CaptureScreenshotResult.Failure(describe(outcome, bytesWritten))
        } finally {
            if (!committed) {
                withContext(NonCancellable) { target.discard() }
            }
        }
    }

    private fun describe(outcome: AdbOutcome, bytesWritten: Long): String = when {
        outcome is AdbOutcome.Completed && bytesWritten == 0L -> "Screenshot capture produced no data"
        outcome is AdbOutcome.Completed -> "screencap exited with code ${outcome.exitCode}"
        outcome is AdbOutcome.TimedOut -> "Screenshot capture timed out"
        outcome is AdbOutcome.Cancelled -> "Screenshot capture was cancelled"
        outcome is AdbOutcome.TransportFailure -> outcome.reason
        outcome is AdbOutcome.Unsupported -> outcome.reason
        else -> "Screenshot capture failed"
    }
}
