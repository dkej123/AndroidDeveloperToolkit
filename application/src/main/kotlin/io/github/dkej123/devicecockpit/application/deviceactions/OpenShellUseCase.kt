package io.github.dkej123.devicecockpit.application.deviceactions

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deviceactions.DeviceActionResult
import io.github.dkej123.devicecockpit.domain.deviceactions.ShellSessionIntent
import io.github.dkej123.devicecockpit.domain.deviceactions.TerminalLaunchResult
import io.github.dkej123.devicecockpit.domain.deviceactions.TerminalLauncher
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolLocator

/**
 * Runs task 016's Open-shell basic action (ADR 0007): resolves `adb`'s real, validated executable
 * path via [toolLocator] — never a hardcoded `"adb"` literal, since a configured/SDK path can
 * differ from whatever is on `PATH` — then asks [terminalLauncher] to pre-seed a new terminal tab
 * with the resulting [ShellSessionIntent]. Both a tool-discovery failure and a terminal-adapter
 * ("shell-adapter") failure are preserved as a truthful [DeviceActionResult.Failure], never a
 * silent no-op, per ADR 0007.
 */
class OpenShellUseCase(
    private val toolLocator: ToolLocator,
    private val terminalLauncher: TerminalLauncher,
) {
    suspend fun openShell(serial: DeviceSerial): DeviceActionResult {
        return when (val outcome = toolLocator.locate(ToolId.Adb)) {
            is DiscoveryOutcome.Found -> {
                val intent = ShellSessionIntent(serial, outcome.tool.path.value)
                when (val launch = terminalLauncher.openShell(intent)) {
                    TerminalLaunchResult.Launched -> DeviceActionResult.Success
                    is TerminalLaunchResult.Unavailable -> DeviceActionResult.Failure(launch.reason)
                }
            }
            is DiscoveryOutcome.Failed -> DeviceActionResult.Failure(describe(outcome.error))
        }
    }

    private fun describe(error: DiscoveryError): String = when (error) {
        is DiscoveryError.ToolNotFound -> "adb was not found"
        is DiscoveryError.ExecutableInvalid -> error.reason
        is DiscoveryError.VersionQueryFailed -> error.reason
    }
}
