package dev.acme.adbtoolbox.domain.capture

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the on-device full-shot helper left behind (ADR 0013). */
sealed interface FullShotRender {
    /** A PNG of the foreground app's full content at [remotePath]; [truncated] when it may continue below. */
    data class Rendered(val remotePath: String, val widthPx: Int, val heightPx: Int, val truncated: Boolean) : FullShotRender

    /** [helperStarted] is `false` when the pushed helper itself could not run, so it should be pushed again. */
    data class Failed(val reason: String, val helperStarted: Boolean = true) : FullShotRender
}

/**
 * Renders the foreground app of a device at its full content height on the device (ADR 0013): the
 * device helper moves the app's task to a tall virtual display, captures it and moves it back.
 */
fun interface FullShotRenderer {
    suspend fun render(serial: DeviceSerial): FullShotRender
}

private const val HEADER = "ADBTOOLBOX-FULLSHOT 1"
private const val MAIN_CLASS = "dev.acme.adbtoolbox.devicehelper.FullShotMain"
private const val FILE_PREFIX = "file="
private const val SIZE_PREFIX = "size="
private const val TRUNCATED_PREFIX = "truncated="
private const val ERROR_PREFIX = "error="
private val SIZE = Regex("""(\d+)x(\d+)""")

/**
 * Command/parser for the helper's `FullShotMain` entry point: it prints [HEADER], then `file=`,
 * `size=<w>x<h>` and `truncated=0|1`, or `error=<exception>`, then `END`.
 */
object FullShotHelperCommand {

    /** Where the helper writes its PNG; a fixed name, so a leftover is overwritten by the next capture. */
    const val OUTPUT_PATH = "/data/local/tmp/adbtoolbox-fullshot.png"

    /**
     * A few seconds on a phone; the helper itself gives the app 15 s to settle, and a software
     * emulator next to a running IDE needs over 40 s just to start `app_process` and move the task.
     */
    val TIMEOUT: Duration = 90.seconds

    /** [remotePath] is the pushed helper jar (`AppInfoCommand.remotePath`), safe as a literal `CLASSPATH=`. */
    fun request(serial: DeviceSerial, remotePath: String): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("CLASSPATH=$remotePath"),
                ShellToken.Literal("app_process"),
                ShellToken.Literal("/"),
                ShellToken.Literal(MAIN_CLASS),
                ShellToken.Value(ShellValue.of(OUTPUT_PATH)),
            ),
        ),
        timeout = TIMEOUT,
    )

    /** Streams the rendered PNG back through the binary-safe `exec-out` path. */
    fun readRequest(serial: DeviceSerial, remotePath: String): AdbDeviceRequest =
        AdbDeviceRequest(serial = serial, operation = AdbOperation.Exec(listOf("cat", remotePath)))

    fun removeRequest(serial: DeviceSerial, remotePath: String): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(ShellToken.Literal("rm"), ShellToken.Literal("-f"), ShellToken.Value(ShellValue.of(remotePath))),
        ),
    )

    fun parse(result: AdbTextResult): FullShotRender {
        when (val outcome = result.outcome) {
            is AdbOutcome.Completed -> Unit
            AdbOutcome.TimedOut -> return FullShotRender.Failed("Full screenshot timed out")
            AdbOutcome.Cancelled -> return FullShotRender.Failed("Full screenshot was cancelled")
            is AdbOutcome.TransportFailure -> return FullShotRender.Failed(outcome.reason)
            is AdbOutcome.Unsupported -> return FullShotRender.Failed(outcome.reason)
        }
        val lines = result.stdout.lines().map { it.trim() }
        if (HEADER !in lines) return FullShotRender.Failed("Full screenshot helper did not start", helperStarted = false)
        fun value(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

        value(ERROR_PREFIX)?.let { error ->
            return FullShotRender.Failed("Full screenshot failed: ${error.substringAfter(": ")}")
        }
        val file = value(FILE_PREFIX)?.takeIf { it.isNotBlank() }
        val size = value(SIZE_PREFIX)?.let { SIZE.matchEntire(it) }
        if (file == null || size == null) return FullShotRender.Failed("Full screenshot helper reported no image")
        return FullShotRender.Rendered(
            remotePath = file,
            widthPx = size.groupValues[1].toInt(),
            heightPx = size.groupValues[2].toInt(),
            truncated = value(TRUNCATED_PREFIX) == "1",
        )
    }
}
