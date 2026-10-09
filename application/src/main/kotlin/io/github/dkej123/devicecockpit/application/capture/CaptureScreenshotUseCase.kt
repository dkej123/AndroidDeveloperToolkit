package io.github.dkej123.devicecockpit.application.capture

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.capture.CaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.CaptureLocation
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.capture.FullShotHelperCommand
import io.github.dkej123.devicecockpit.domain.capture.FullShotRender
import io.github.dkej123.devicecockpit.domain.capture.FullShotRenderer
import io.github.dkej123.devicecockpit.domain.capture.ImageClipboard
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** `design/IMPLEMENTATION.md` §4's `adb -s $S exec-out screencap -p` argv — never shell-parsed. */
private val SCREENCAP_ARGUMENTS = listOf("screencap", "-p")

/** Bounded request/response default (ADR 0005); a screenshot is a one-shot call, not a stream. */
val DEFAULT_SCREENSHOT_TIMEOUT: Duration = 15.seconds

/** What a screenshot shows: the screen as it is, or the foreground app's whole scrolling content (ADR 0013). */
enum class ScreenshotMode { Visible, FullContent }

/** What [CaptureScreenshotUseCase.capture] produced. */
sealed interface CaptureScreenshotResult {
    /**
     * [truncated]: a full-content capture whose content may continue below what was captured.
     * [copiedToClipboard]: the image is also on the clipboard (task 061).
     */
    data class Success(val location: CaptureLocation, val truncated: Boolean = false, val copiedToClipboard: Boolean = false) : CaptureScreenshotResult
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
 *
 * [ScreenshotMode.FullContent] first has [fullShotRenderer] render the app at its full content height
 * on the device (ADR 0013), then streams that PNG with `exec-out cat` under the same commit/discard
 * rules, saved as `<name>-full.png`; the device copy is removed whatever the outcome.
 */
class CaptureScreenshotUseCase(
    private val adbTransport: AdbTransport,
    private val captureDestination: CaptureDestination,
    private val fileNamePolicy: FileNamePolicy,
    private val clock: Clock = Clock.System,
    private val timeout: Duration = DEFAULT_SCREENSHOT_TIMEOUT,
    private val fullShotRenderer: FullShotRenderer? = null,
    private val clipboard: ImageClipboard? = null,
    /** Settings › Capture › "Also copy screenshots to the clipboard" (default on, design §11). */
    private val copyToClipboard: () -> Boolean = { true },
) {
    suspend fun capture(serial: DeviceSerial, mode: ScreenshotMode = ScreenshotMode.Visible): CaptureScreenshotResult {
        val baseFileName = fileNamePolicy.baseFileName(clock.now())
        return when (mode) {
            ScreenshotMode.Visible -> save(baseFileName, AdbOperation.Exec(SCREENCAP_ARGUMENTS), serial)
            ScreenshotMode.FullContent -> captureFullContent(serial, baseFileName)
        }
    }

    private suspend fun captureFullContent(serial: DeviceSerial, baseFileName: String): CaptureScreenshotResult {
        val renderer = fullShotRenderer ?: return CaptureScreenshotResult.Failure("Full screenshots are not available")
        val render = when (val result = renderer.render(serial)) {
            is FullShotRender.Failed -> return CaptureScreenshotResult.Failure(result.reason)
            is FullShotRender.Rendered -> result
        }
        try {
            val saved = save(fullContentFileName(baseFileName), FullShotHelperCommand.readRequest(serial, render.remotePath).operation, serial)
            return if (saved is CaptureScreenshotResult.Success) saved.copy(truncated = render.truncated) else saved
        } finally {
            withContext(NonCancellable) { adbTransport.executeText(FullShotHelperCommand.removeRequest(serial, render.remotePath)) }
        }
    }

    private fun fullContentFileName(baseFileName: String): String {
        val dot = baseFileName.lastIndexOf('.')
        return if (dot < 0) "$baseFileName-full" else "${baseFileName.substring(0, dot)}-full${baseFileName.substring(dot)}"
    }

    private suspend fun save(baseFileName: String, operation: AdbOperation, serial: DeviceSerial): CaptureScreenshotResult {
        val target = captureDestination.beginCapture(baseFileName)
        var bytesWritten = 0L
        // Kept only when it goes to the clipboard; a copy failure never fails the save.
        val forClipboard = if (clipboard != null && copyToClipboard()) mutableListOf<ByteArray>() else null
        val countingSink = ByteSink { chunk ->
            bytesWritten += chunk.size
            target.sink.write(chunk)
            forClipboard?.add(chunk.copyOf())
        }
        var committed = false
        try {
            val outcome = adbTransport.executeBinary(
                AdbDeviceRequest(serial = serial, operation = operation, timeout = timeout),
                countingSink,
            )
            if (outcome is AdbOutcome.Completed && outcome.exitCode.let { it == null || it == 0 } && bytesWritten > 0L) {
                val location = target.commit()
                committed = true
                val copied = forClipboard != null && runCatching { clipboard!!.copyPng(forClipboard.fold(ByteArray(0)) { all, part -> all + part }) }.getOrDefault(false)
                return CaptureScreenshotResult.Success(location, copiedToClipboard = copied)
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
