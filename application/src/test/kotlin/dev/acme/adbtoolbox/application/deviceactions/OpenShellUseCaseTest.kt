package dev.acme.adbtoolbox.application.deviceactions

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionResult
import dev.acme.adbtoolbox.domain.deviceactions.FakeTerminalLauncher
import dev.acme.adbtoolbox.domain.deviceactions.ShellSessionIntent
import dev.acme.adbtoolbox.domain.deviceactions.TerminalLaunchResult
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("R58N90ABCDE")

private val FOUND_ADB = DiscoveryOutcome.Found(
    DiscoveredTool(
        id = ToolId.Adb,
        source = ToolSource.PathFallback,
        path = ToolExecutablePath.of("/opt/homebrew/bin/adb"),
        version = ToolVersion.of("35.0.2"),
    ),
)

/**
 * Covers task 016's Open-shell use case: it must resolve `adb`'s real path before building the
 * [ShellSessionIntent] (never a hardcoded `"adb"` literal, since a configured/SDK path may differ
 * from `PATH`), and must preserve both a discovery failure and a [TerminalLauncher] adapter
 * failure ("shell-adapter failure" in the task's TDD plan) as a truthful [DeviceActionResult].
 */
class OpenShellUseCaseTest {

    @Test
    fun `opens a shell session for the exact serial using the resolved adb path`() = runTest {
        val toolLocator = FakeToolLocator { FOUND_ADB }
        val terminalLauncher = FakeTerminalLauncher()
        val useCase = OpenShellUseCase(toolLocator, terminalLauncher)

        val result = useCase.openShell(SERIAL)

        result shouldBe DeviceActionResult.Success
        toolLocator.locateCalls shouldBe listOf(ToolId.Adb)
        terminalLauncher.openShellCalls.single() shouldBe ShellSessionIntent(SERIAL, "/opt/homebrew/bin/adb")
    }

    @Test
    fun `a tool-discovery failure is preserved as a failure, never launching a shell`() = runTest {
        val error = DiscoveryError.ToolNotFound(ToolId.Adb, attemptedSources = emptyList())
        val toolLocator = FakeToolLocator { DiscoveryOutcome.Failed(error) }
        val terminalLauncher = FakeTerminalLauncher()
        val useCase = OpenShellUseCase(toolLocator, terminalLauncher)

        val result = useCase.openShell(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        terminalLauncher.openShellCalls shouldBe emptyList()
    }

    @Test
    fun `a terminal-adapter (shell-adapter) failure is preserved with its own reason`() = runTest {
        val toolLocator = FakeToolLocator { FOUND_ADB }
        val terminalLauncher = FakeTerminalLauncher { TerminalLaunchResult.Unavailable("Open shell requires the Terminal plugin") }
        val useCase = OpenShellUseCase(toolLocator, terminalLauncher)

        val result = useCase.openShell(SERIAL)

        result.shouldBeInstanceOf<DeviceActionResult.Failure>()
        (result as DeviceActionResult.Failure).reason shouldBe "Open shell requires the Terminal plugin"
    }
}
