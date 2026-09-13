@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.settings

import dev.acme.adbtoolbox.domain.discovery.FakeExecutableFileProbe
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.settings.FakeDirectoryProbe
import dev.acme.adbtoolbox.domain.settings.SettingsDependency
import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import dev.acme.adbtoolbox.domain.settings.SettingsInvalidationPort
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.domain.settings.SettingsState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SettingsViewModelTest {
    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    private class Repository(initial: SettingsState) : SettingsRepository {
        var stored = initial
        var writes = emptyList<SettingsState>()

        override suspend fun readSettings(): SettingsState = stored

        override suspend fun writeSettings(state: SettingsState) {
            stored = state
            writes = writes + state
        }
    }

    private class Invalidation : SettingsInvalidationPort {
        var calls = emptyList<Set<SettingsDependency>>()

        override suspend fun invalidate(changed: Set<SettingsDependency>) {
            calls = calls + listOf(changed)
        }
    }

    private class Harness(initial: SettingsState = SettingsState.DEFAULT) {
        val scope = TestScope()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val repository = Repository(initial)
        val executableProbe = FakeExecutableFileProbe()
        val directoryProbe = FakeDirectoryProbe()
        val invalidation = Invalidation()
        val viewModel = SettingsViewModel(
            scope = scope,
            dispatchers = TestDispatchers(dispatcher),
            settings = SettingsUseCase(repository, executableProbe, directoryProbe, invalidation),
        )
    }

    @Test
    fun `initial load publishes persisted settings as the clean editable draft`() = runTest {
        val persisted = SettingsState(adbPathOverride = "/tools/adb", logcatBufferSizeKb = 4096)
        val h = Harness(persisted)

        h.scope.runCurrent()

        h.viewModel.state.value shouldBe SettingsViewState(
            persisted = persisted,
            draft = persisted,
            isLoading = false,
        )
    }

    @Test
    fun `edit intents update the draft synchronously and mark it modified`() = runTest {
        val h = Harness()
        h.scope.runCurrent()

        h.viewModel.handle(SettingsIntent.UpdateAdbPath("/tools/adb"))
        h.viewModel.handle(SettingsIntent.UpdateScrcpyPath("/tools/scrcpy"))
        h.viewModel.handle(SettingsIntent.UpdateCaptureDirectory("/captures"))
        h.viewModel.handle(SettingsIntent.UpdateLogcatBufferSizeKb(8192))

        h.viewModel.state.value.draft shouldBe SettingsState(
            adbPathOverride = "/tools/adb",
            scrcpyPathOverride = "/tools/scrcpy",
            captureDirectory = "/captures",
            logcatBufferSizeKb = 8192,
        )
        h.viewModel.state.value.isModified shouldBe true
    }

    @Test
    fun `reset restores the last persisted values and clears validation errors`() = runTest {
        val persisted = SettingsState(logcatBufferSizeKb = 4096)
        val h = Harness(persisted)
        h.scope.runCurrent()
        h.viewModel.handle(SettingsIntent.UpdateAdbPath("/bad/adb"))
        h.viewModel.handle(SettingsIntent.Apply)
        h.scope.runCurrent()
        h.viewModel.state.value.validationErrors shouldBe setOf(SettingsFieldError.AdbPathNotExecutable)

        h.viewModel.handle(SettingsIntent.Reset)

        h.viewModel.state.value.draft shouldBe persisted
        h.viewModel.state.value.validationErrors shouldBe emptySet()
        h.viewModel.state.value.isModified shouldBe false
    }

    @Test
    fun `invalid apply keeps the edited draft and exposes errors without persistence or invalidation`() = runTest {
        val h = Harness()
        h.scope.runCurrent()
        h.viewModel.handle(SettingsIntent.UpdateScrcpyPath("/bad/scrcpy"))

        h.viewModel.handle(SettingsIntent.Apply)
        h.scope.runCurrent()

        h.viewModel.state.value.draft.scrcpyPathOverride shouldBe "/bad/scrcpy"
        h.viewModel.state.value.validationErrors shouldBe setOf(SettingsFieldError.ScrcpyPathNotExecutable)
        h.viewModel.state.value.isApplying shouldBe false
        h.repository.writes shouldBe emptyList()
        h.invalidation.calls shouldBe emptyList()
    }

    @Test
    fun `valid apply persists normalized values invalidates dependencies and leaves a clean draft`() = runTest {
        val h = Harness()
        h.scope.runCurrent()
        h.executableProbe.markExecutable("/tools/adb")
        h.viewModel.handle(SettingsIntent.UpdateAdbPath("  /tools/adb "))

        h.viewModel.handle(SettingsIntent.Apply)
        h.scope.runCurrent()

        val applied = SettingsState(adbPathOverride = "/tools/adb")
        h.repository.writes shouldBe listOf(applied)
        h.invalidation.calls shouldBe listOf(setOf(SettingsDependency.AdbPath))
        h.viewModel.state.value.persisted shouldBe applied
        h.viewModel.state.value.draft shouldBe applied
        h.viewModel.state.value.isModified shouldBe false
    }
}
