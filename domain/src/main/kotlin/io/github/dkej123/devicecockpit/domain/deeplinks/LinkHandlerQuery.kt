package io.github.dkej123.devicecockpit.domain.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import kotlin.time.Duration.Companion.seconds

/** What `resolve-activity` picks for a link tap: one activity, the system chooser, or nothing. */
sealed interface ResolvedHandler {
    data class Direct(val component: String) : ResolvedHandler
    data object Chooser : ResolvedHandler
    data object None : ResolvedHandler
}

/**
 * "Who handles this link?" without launching anything (design/DEEP_LINK_DIALOG.md §2b): the VIEW +
 * BROWSABLE matches from `cmd package query-activities` and the verdict of `resolve-activity`.
 * Even with `--brief` both print `priority=…` lines; the component is the line with a `/`.
 */
object LinkHandlerQuery {
    private val component = Regex("""^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$""")

    fun matches(serial: DeviceSerial, uri: String): AdbDeviceRequest = request(serial, "query-activities", uri)
    fun resolve(serial: DeviceSerial, uri: String): AdbDeviceRequest = request(serial, "resolve-activity", uri)

    fun parseMatches(output: String): List<String> = components(output)

    fun parseResolved(output: String): ResolvedHandler {
        val found = components(output).firstOrNull() ?: return ResolvedHandler.None
        return if (AmStartOutput.isChooser(found)) ResolvedHandler.Chooser else ResolvedHandler.Direct(found)
    }

    private fun components(output: String) = output.lineSequence().map(String::trim).filter(component::matches).toList()

    private fun request(serial: DeviceSerial, verb: String, uri: String): AdbDeviceRequest {
        fun l(value: String) = ShellToken.Literal(value)
        fun v(value: String) = ShellToken.Value(ShellValue.of(value))
        return AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
            l("cmd"), l("package"), l(verb), l("--brief"), l("-a"), v("android.intent.action.VIEW"),
            l("-c"), v("android.intent.category.BROWSABLE"), l("-d"), v(uri),
        )), timeout = 3.seconds)
    }
}
