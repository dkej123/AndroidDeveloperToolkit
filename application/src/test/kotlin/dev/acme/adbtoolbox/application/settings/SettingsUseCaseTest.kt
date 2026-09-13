package dev.acme.adbtoolbox.application.settings

import dev.acme.adbtoolbox.domain.discovery.FakeExecutableFileProbe
import dev.acme.adbtoolbox.domain.settings.FakeDirectoryProbe
import dev.acme.adbtoolbox.domain.settings.SettingsDependency
import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import dev.acme.adbtoolbox.domain.settings.SettingsInvalidationPort
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.domain.settings.SettingsState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SettingsUseCaseTest {
    private class RecordingRepository(var stored: SettingsState) : SettingsRepository {
        var writes = emptyList<SettingsState>()

        override suspend fun readSettings(): SettingsState = stored

        override suspend fun writeSettings(state: SettingsState) {
            writes = writes + state
            stored = state
        }
    }

    private class RecordingInvalidation : SettingsInvalidationPort {
        var calls = emptyList<Set<SettingsDependency>>()

        override suspend fun invalidate(changed: Set<SettingsDependency>) {
            calls = calls + listOf(changed)
        }
    }

    private data class Harness(
        val repository: RecordingRepository,
        val executableProbe: FakeExecutableFileProbe,
        val directoryProbe: FakeDirectoryProbe,
        val invalidation: RecordingInvalidation,
        val useCase: SettingsUseCase,
    )

    private fun harness(stored: SettingsState = SettingsState.DEFAULT): Harness {
        val repository = RecordingRepository(stored)
        val executableProbe = FakeExecutableFileProbe()
        val directoryProbe = FakeDirectoryProbe()
        val invalidation = RecordingInvalidation()
        return Harness(
            repository,
            executableProbe,
            directoryProbe,
            invalidation,
            SettingsUseCase(repository, executableProbe, directoryProbe, invalidation),
        )
    }

    @Test
    fun `load returns persisted project settings`() = runTest {
        val stored = SettingsState(adbPathOverride = "/tools/adb", logcatBufferSizeKb = 4096)
        val h = harness(stored)

        h.useCase.load() shouldBe stored
    }

    @Test
    fun `invalid apply reports every validation error without writing or invalidating`() = runTest {
        val h = harness()
        val result = h.useCase.apply(
            SettingsState(
                adbPathOverride = "/bad/adb",
                scrcpyPathOverride = "/bad/scrcpy",
                captureDirectory = "/bad/captures",
                logcatBufferSizeKb = 1,
            ),
        )

        result shouldBe SettingsApplyResult.Invalid(
            setOf(
                SettingsFieldError.AdbPathNotExecutable,
                SettingsFieldError.ScrcpyPathNotExecutable,
                SettingsFieldError.CaptureDirectoryInvalid,
                SettingsFieldError.LogcatBufferSizeOutOfRange,
            ),
        )
        h.repository.writes shouldBe emptyList()
        h.invalidation.calls shouldBe emptyList()
    }

    @Test
    fun `valid apply persists normalized state and invalidates only changed dependencies`() = runTest {
        val h = harness(SettingsState(adbPathOverride = "/old/adb", logcatBufferSizeKb = 4096))
        h.executableProbe.markExecutable("/new/adb")
        h.executableProbe.markExecutable("/tools/scrcpy")
        h.directoryProbe.markValidDirectory("/captures")

        val result = h.useCase.apply(
            SettingsState(
                adbPathOverride = " /new/adb ",
                scrcpyPathOverride = "/tools/scrcpy",
                captureDirectory = "/captures",
                logcatBufferSizeKb = 4096,
            ),
        )

        val expected = SettingsState(
            adbPathOverride = "/new/adb",
            scrcpyPathOverride = "/tools/scrcpy",
            captureDirectory = "/captures",
            logcatBufferSizeKb = 4096,
        )
        result shouldBe SettingsApplyResult.Applied(expected)
        h.repository.writes shouldBe listOf(expected)
        h.invalidation.calls shouldBe listOf(
            setOf(
                SettingsDependency.AdbPath,
                SettingsDependency.ScrcpyPath,
                SettingsDependency.CaptureDirectory,
            ),
        )
    }

    @Test
    fun `unchanged valid apply writes but does not invalidate runtime state`() = runTest {
        val stored = SettingsState(captureDirectory = "/captures")
        val h = harness(stored)
        h.directoryProbe.markValidDirectory("/captures")

        h.useCase.apply(stored)

        h.repository.writes shouldBe listOf(stored)
        h.invalidation.calls shouldBe emptyList()
    }

    @Test
    fun `buffer-only apply invalidates only Logcat buffer state`() = runTest {
        val h = harness()
        val candidate = SettingsState(logcatBufferSizeKb = 8192)

        h.useCase.apply(candidate)

        h.invalidation.calls shouldBe listOf(setOf(SettingsDependency.LogcatBufferSize))
    }
}
