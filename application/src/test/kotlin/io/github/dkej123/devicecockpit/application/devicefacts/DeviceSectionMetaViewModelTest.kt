package io.github.dkej123.devicecockpit.application.devicefacts

import io.github.dkej123.devicecockpit.domain.discovery.DiscoveredTool
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolExecutablePath
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.settings.SettingsRepository
import io.github.dkej123.devicecockpit.domain.settings.SettingsState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSectionMetaViewModelTest {

    private class Dispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    private class Settings(var state: SettingsState = SettingsState()) : SettingsRepository {
        override suspend fun readSettings(): SettingsState = state
        override suspend fun writeSettings(state: SettingsState) {
            this.state = state
        }
    }

    private fun scrcpy(version: String) = DiscoveryOutcome.Found(
        DiscoveredTool(ToolId.Scrcpy, ToolSource.PathFallback, ToolExecutablePath.of("/opt/scrcpy/scrcpy"), ToolVersion.of(version)),
    )

    private val notFound = DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList()))

    private val scope = TestScope()

    private fun viewModel(
        settings: SettingsRepository,
        scrcpy: () -> DiscoveryOutcome,
    ) = DeviceSectionMetaViewModel(
        scope = scope,
        dispatchers = Dispatchers(StandardTestDispatcher(scope.testScheduler)),
        toolLocator = FakeToolLocator { scrcpy() },
        settings = settings,
        homeDirectory = "/home/dev",
        defaultCaptureDirectory = "/home/dev/Desktop",
    )

    @Test
    fun `mirroring meta shows the installed scrcpy version`() {
        val viewModel = viewModel(Settings()) { scrcpy("3.1") }
        scope.advanceUntilIdle()

        viewModel.state.value.mirroring shouldBe "scrcpy 3.1"
    }

    @Test
    fun `mirroring meta says so when scrcpy is not installed`() {
        val viewModel = viewModel(Settings()) { notFound }
        scope.advanceUntilIdle()

        viewModel.state.value.mirroring shouldBe "scrcpy not found"
    }

    @Test
    fun `capture meta shows the default directory relative to home`() {
        val viewModel = viewModel(Settings()) { notFound }
        scope.advanceUntilIdle()

        viewModel.state.value.capture shouldBe "~/Desktop"
    }

    @Test
    fun `capture meta follows the configured directory after a refresh`() {
        val settings = Settings()
        var installed = notFound as DiscoveryOutcome
        val viewModel = viewModel(settings) { installed }
        scope.advanceUntilIdle()

        settings.state = SettingsState(captureDirectory = "/mnt/captures")
        installed = scrcpy("2.4")
        viewModel.refresh()
        scope.advanceUntilIdle()

        viewModel.state.value shouldBe DeviceSectionMeta(mirroring = "scrcpy 2.4", capture = "/mnt/captures")
    }

    @Test
    fun `a configured directory inside home is abbreviated with a tilde`() {
        val viewModel = viewModel(Settings(SettingsState(captureDirectory = "/home/dev/shots/"))) { notFound }
        scope.advanceUntilIdle()

        viewModel.state.value.capture shouldBe "~/shots"
    }
}
