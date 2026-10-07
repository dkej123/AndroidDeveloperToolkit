package dev.acme.adbtoolbox.domain.foreground

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What is in front on the device (design §3a states). */
sealed interface ForegroundState {
    /** A resumed activity of [packageName] for Android user [userId]; [activity] is its class, e.g. `.checkout.CheckoutActivity`. */
    data class App(val packageName: String, val activity: String, val userId: Int = 0) : ForegroundState

    /** The notification shade over [behind] (System UI has the input focus). */
    data class SystemUi(val behind: App?) : ForegroundState

    data class Home(val launcherPackage: String) : ForegroundState

    data object Locked : ForegroundState

    data object Nothing : ForegroundState

    data class Unknown(val reason: String) : ForegroundState
}

/**
 * Reads the foreground app on demand (task 062, design §3a) in one round trip: the resumed activity
 * from `dumpsys activity activities` (`topResumedActivity=` on API 29+, `ResumedActivity:` and
 * `mResumedActivity:` on older releases), then the window manager's focus and keyguard state from
 * `dumpsys window` — the shade and the lock screen are System UI windows over a still-resumed app.
 * The output format is not an API: everything is parsed defensively.
 *
 * Partly from Oh My Android, MIT — `Sources/Core/Android/ForegroundAppReader.swift`.
 */
object ForegroundAppCommand {
    private const val SEPARATOR = "--adbtoolbox-window--"
    private val ACTIVITY_RECORD = Regex("""ActivityRecord\{\S+ u(\d+) ([\w.]+)/([\w.$]+)""")
    private val RESUMED_LINE = Regex("""^\s*(topResumedActivity=|ResumedActivity:|mResumedActivity:|Resumed:)""")
    private val FOCUS_WINDOW = Regex("""mCurrentFocus=Window\{\S+ u\d+ ([^}]+)}""")

    fun request(serial: DeviceSerial): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                *(
                    "dumpsys activity activities | grep -E 'topResumedActivity=|ResumedActivity:|Resumed:' ; " +
                        "echo $SEPARATOR ; dumpsys window | grep -E 'mCurrentFocus=|isKeyguardShowing='"
                    ).split(' ').map { ShellToken.Literal(it) }.toTypedArray(),
            ),
        ),
    )

    /** [launcherPackage] is the device's HOME activity package ([HomeActivityCommand]), when known. */
    fun parse(result: AdbTextResult, launcherPackage: String?): ForegroundState {
        if (result.outcome !is AdbOutcome.Completed) return ForegroundState.Unknown(result.outcome.describe())
        val (activities, window) = result.stdout.split(SEPARATOR).let { it.first() to it.getOrElse(1) { "" } }
        val resumed = activities.lineSequence().filter { RESUMED_LINE.containsMatchIn(it) }
            .firstNotNullOfOrNull { ACTIVITY_RECORD.find(it) }
            ?.let { ForegroundState.App(packageName = it.groupValues[2], activity = it.groupValues[3], userId = it.groupValues[1].toInt()) }
        if (Regex("""isKeyguardShowing=true""").containsMatchIn(window)) return ForegroundState.Locked
        val focus = FOCUS_WINDOW.find(window)?.groupValues?.get(1)?.trim()
        if (focus == "NotificationShade" || focus == "StatusBar") return ForegroundState.SystemUi(resumed)
        return when {
            resumed == null && activities.isBlank() && window.isBlank() && result.stderr.isNotBlank() ->
                ForegroundState.Unknown(result.stderr.trim())
            resumed == null -> ForegroundState.Nothing
            resumed.packageName == launcherPackage -> ForegroundState.Home(resumed.packageName)
            else -> resumed
        }
    }

    private fun AdbOutcome.describe(): String = when (this) {
        AdbOutcome.TimedOut -> "dumpsys activity activities — timed out"
        AdbOutcome.Cancelled -> "cancelled"
        is AdbOutcome.TransportFailure -> reason
        is AdbOutcome.Unsupported -> reason
        is AdbOutcome.Completed -> "no output"
    }
}

/** The device's home screen app: the default HOME activity, read once per device. */
object HomeActivityCommand {
    fun request(serial: DeviceSerial) = shell(
        serial, "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
    )

    fun parse(result: AdbTextResult): String? = result.stdout.lineSequence().map(String::trim)
        .lastOrNull { '/' in it && !it.contains(' ') }?.substringBefore('/')
}

/** The process of a package: PID and how long it has been running (design §3a "Process"). */
data class ProcessInfo(val pid: Int, val runningFor: Duration?)

object ProcessInfoCommand {
    fun pidRequest(serial: DeviceSerial, packageName: String) = AdbDeviceRequest(
        serial,
        AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("pidof"), ShellToken.Literal("-s"), ShellToken.Value(ShellValue.of(packageName)))),
    )

    fun elapsedRequest(serial: DeviceSerial, pid: Int) = shell(serial, "ps", "-o", "etime=", "-p", pid.toString())

    /** Nothing printed (exit 1) means the package has no running process. */
    fun parsePid(result: AdbTextResult): Int? = result.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /** toybox `etime`: `[[dd-]hh:]mm:ss`. */
    fun parseElapsed(result: AdbTextResult): Duration? {
        val text = result.stdout.trim()
        val match = Regex("""^(?:(\d+)-)?(?:(\d+):)?(\d+):(\d+)$""").matchEntire(text) ?: return null
        val (d, h, m, s) = match.destructured
        return (d.toIntOrNull() ?: 0).days + (h.toIntOrNull() ?: 0).hours + m.toInt().minutes + s.toInt().seconds
    }
}

private fun shell(serial: DeviceSerial, vararg literals: String) =
    AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(*literals.map { ShellToken.Literal(it) }.toTypedArray())))
