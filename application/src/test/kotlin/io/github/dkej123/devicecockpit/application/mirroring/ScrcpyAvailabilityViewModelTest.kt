@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.mirroring

import io.github.dkej123.devicecockpit.domain.discovery.DiscoveredTool
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeHostPlatformProvider
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.OperatingSystem
import io.github.dkej123.devicecockpit.domain.discovery.ToolExecutablePath
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test

private class AvailabilityDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val found = DiscoveryOutcome.Found(
    DiscoveredTool(ToolId.Scrcpy, ToolSource.PathFallback, ToolExecutablePath.of("/opt/homebrew/bin/scrcpy"), ToolVersion.of("3.1")),
)
private val notFound = DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, listOf(ToolSource.PathFallback)))

class ScrcpyAvailabilityViewModelTest {

    private class Harness(os: OperatingSystem = OperatingSystem.MacOs, var outcome: DiscoveryOutcome = found) {
        val scope = TestScope()
        val locator = FakeToolLocator { outcome }
        val viewModel = ScrcpyAvailabilityViewModel(
            scope = scope,
            dispatchers = AvailabilityDispatchers(StandardTestDispatcher(scope.testScheduler)),
            toolLocator = locator,
            hostPlatform = FakeHostPlatformProvider(os),
        )
    }

    @Test
    fun `a resolved scrcpy is Available with its version`() {
        val h = Harness()

        h.scope.advanceUntilIdle()

        h.viewModel.state.value shouldBe ScrcpyAvailability.Available(ToolVersion.of("3.1"))
    }

    @Test
    fun `a missing scrcpy says so and gives this OS's install command`() {
        val h = Harness(os = OperatingSystem.MacOs, outcome = notFound)

        h.scope.advanceUntilIdle()

        val missing = h.viewModel.state.value.shouldBeInstanceOf<ScrcpyAvailability.Missing>()
        missing.reason shouldBe "scrcpy is not installed, or not on PATH."
        missing.installCommand shouldBe "brew install scrcpy"
    }

    @Test
    fun `each OS gets its own package-manager command`() {
        val windows = Harness(os = OperatingSystem.Windows, outcome = notFound).also { it.scope.advanceUntilIdle() }
        val linux = Harness(os = OperatingSystem.Linux, outcome = notFound).also { it.scope.advanceUntilIdle() }

        (windows.viewModel.state.value as ScrcpyAvailability.Missing).installCommand shouldBe "winget install --exact Genymobile.scrcpy"
        (linux.viewModel.state.value as ScrcpyAvailability.Missing).installCommand shouldBe "sudo apt install scrcpy"
    }

    @Test
    fun `a broken path from Settings points at Settings rather than installing`() {
        val h = Harness(
            outcome = DiscoveryOutcome.Failed(
                DiscoveryError.ExecutableInvalid(ToolId.Scrcpy, ToolSource.ConfiguredPath, "/Users/me/scrcpy", "file does not exist"),
            ),
        )

        h.scope.advanceUntilIdle()

        val missing = h.viewModel.state.value.shouldBeInstanceOf<ScrcpyAvailability.Missing>()
        missing.reason shouldBe "The scrcpy path set in Settings is not usable (/Users/me/scrcpy: file does not exist)."
        missing.configuredPathInvalid shouldBe true
    }

    @Test
    fun `a scrcpy that fails to run names the path and the reason`() {
        val h = Harness(
            outcome = DiscoveryOutcome.Failed(
                DiscoveryError.VersionQueryFailed(ToolId.Scrcpy, ToolSource.PathFallback, "/usr/bin/scrcpy", "exit code 127"),
            ),
        )

        h.scope.advanceUntilIdle()

        h.viewModel.state.value.shouldBeInstanceOf<ScrcpyAvailability.Missing>().reason shouldContain
            "/usr/bin/scrcpy could not be run (exit code 127)"
    }

    @Test
    fun `check again drops the cached lookup and picks up a fresh install`() {
        val h = Harness(outcome = notFound)
        h.scope.advanceUntilIdle()

        h.outcome = found
        h.viewModel.recheck()
        h.viewModel.state.value shouldBe ScrcpyAvailability.Checking
        h.scope.advanceUntilIdle()

        h.locator.invalidated shouldBe listOf(ToolId.Scrcpy)
        h.viewModel.state.value.shouldBeInstanceOf<ScrcpyAvailability.Available>()
    }
}
