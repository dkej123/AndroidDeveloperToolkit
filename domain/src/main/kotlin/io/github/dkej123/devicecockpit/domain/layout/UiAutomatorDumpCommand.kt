package io.github.dkej123.devicecockpit.domain.layout

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken

/** What one `uiautomator dump` produced. */
sealed interface UiDumpRead {
    data class Dumped(val root: UiNode) : UiDumpRead

    /** Nothing printed: uiautomator refused, usually because the screen was still animating. */
    data object Empty : UiDumpRead

    data class Malformed(val reason: String) : UiDumpRead

    data class TransportFailed(val outcome: AdbOutcome) : UiDumpRead
}

/**
 * `uiautomator dump` of the current screen (ADR 0014), in one shell call: dump into the shell user's
 * `/data/local/tmp` (not visible to apps), print it, and remove it whatever happened.
 *
 * Ported from Oh My Android, MIT — `Sources/Core/Android/LayoutSnapshot.swift` (`UIAutomatorSnapshotReader`).
 */
object UiAutomatorDumpCommand {
    private const val FILE = "/data/local/tmp/adbtoolbox-ui.xml"

    fun request(serial: DeviceSerial): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                *"uiautomator dump $FILE >/dev/null 2>&1 && cat $FILE; rm -f $FILE"
                    .split(' ').map { ShellToken.Literal(it) }.toTypedArray(),
            ),
        ),
    )

    fun parse(result: AdbTextResult): UiDumpRead {
        if (result.outcome !is AdbOutcome.Completed) return UiDumpRead.TransportFailed(result.outcome)
        if (result.stdout.isBlank()) return UiDumpRead.Empty
        return when (val parsed = UiAutomatorXmlParser.parse(result.stdout)) {
            is UiTreeParse.Parsed -> UiDumpRead.Dumped(parsed.root)
            is UiTreeParse.Malformed -> UiDumpRead.Malformed(parsed.reason)
        }
    }
}
