package dev.acme.adbtoolbox.domain.settings

import dev.acme.adbtoolbox.domain.discovery.FakeExecutableFileProbe
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SettingsValidationTest {

    @Test
    fun `all-defaults candidate is valid`() = runTest {
        val result = validateSettings(SettingsState.DEFAULT, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Valid(SettingsState.DEFAULT)
    }

    @Test
    fun `blank path fields clear their overrides instead of failing validation`() = runTest {
        val candidate = SettingsState(
            adbPathOverride = "   ",
            scrcpyPathOverride = "\t",
            captureDirectory = "\n",
        )

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Valid(SettingsState.DEFAULT)
    }

    @Test
    fun `path fields are trimmed before probing and returned normalized`() = runTest {
        val executableProbe = FakeExecutableFileProbe().apply {
            markExecutable("/usr/bin/adb")
            markExecutable("/usr/local/bin/scrcpy")
        }
        val directoryProbe = FakeDirectoryProbe().apply { markValidDirectory("/home/user/captures") }
        val candidate = SettingsState(
            adbPathOverride = "  /usr/bin/adb ",
            scrcpyPathOverride = "\t/usr/local/bin/scrcpy\n",
            captureDirectory = " /home/user/captures  ",
        )

        val result = validateSettings(candidate, executableProbe, directoryProbe)

        result shouldBe SettingsValidationResult.Valid(
            SettingsState(
                adbPathOverride = "/usr/bin/adb",
                scrcpyPathOverride = "/usr/local/bin/scrcpy",
                captureDirectory = "/home/user/captures",
            ),
        )
    }

    @Test
    fun `a non-blank adb override that probes as executable is valid`() = runTest {
        val executableProbe = FakeExecutableFileProbe().apply { markExecutable("/usr/bin/adb") }
        val candidate = SettingsState(adbPathOverride = "/usr/bin/adb")

        val result = validateSettings(candidate, executableProbe, FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Valid(candidate)
    }

    @Test
    fun `an adb override that does not probe as executable is rejected`() = runTest {
        val candidate = SettingsState(adbPathOverride = "/not/a/real/adb")

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Invalid(setOf(SettingsFieldError.AdbPathNotExecutable))
    }

    @Test
    fun `a scrcpy override that does not probe as executable is rejected`() = runTest {
        val candidate = SettingsState(scrcpyPathOverride = "/not/a/real/scrcpy")

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Invalid(setOf(SettingsFieldError.ScrcpyPathNotExecutable))
    }

    @Test
    fun `a capture directory that does not probe as a real directory is rejected`() = runTest {
        val candidate = SettingsState(captureDirectory = "/not/a/real/dir")

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Invalid(setOf(SettingsFieldError.CaptureDirectoryInvalid))
    }

    @Test
    fun `a valid capture directory is accepted`() = runTest {
        val directoryProbe = FakeDirectoryProbe().apply { markValidDirectory("/home/user/captures") }
        val candidate = SettingsState(captureDirectory = "/home/user/captures")

        val result = validateSettings(candidate, FakeExecutableFileProbe(), directoryProbe)

        result shouldBe SettingsValidationResult.Valid(candidate)
    }

    @Test
    fun `a buffer size below the minimum is rejected`() = runTest {
        val candidate = SettingsState(logcatBufferSizeKb = SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB - 1)

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Invalid(setOf(SettingsFieldError.LogcatBufferSizeOutOfRange))
    }

    @Test
    fun `a buffer size above the maximum is rejected`() = runTest {
        val candidate = SettingsState(logcatBufferSizeKb = SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB + 1)

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Invalid(setOf(SettingsFieldError.LogcatBufferSizeOutOfRange))
    }

    @Test
    fun `multiple invalid fields are all reported, not just the first`() = runTest {
        val candidate = SettingsState(
            adbPathOverride = "/bad/adb",
            scrcpyPathOverride = "/bad/scrcpy",
            captureDirectory = "/bad/dir",
            logcatBufferSizeKb = -1,
        )

        val result = validateSettings(candidate, FakeExecutableFileProbe(), FakeDirectoryProbe())

        result shouldBe SettingsValidationResult.Invalid(
            setOf(
                SettingsFieldError.AdbPathNotExecutable,
                SettingsFieldError.ScrcpyPathNotExecutable,
                SettingsFieldError.CaptureDirectoryInvalid,
                SettingsFieldError.LogcatBufferSizeOutOfRange,
            ),
        )
    }

    @Test
    fun `boundary buffer sizes at min and max are valid`() = runTest {
        val min = SettingsState(logcatBufferSizeKb = SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB)
        val max = SettingsState(logcatBufferSizeKb = SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB)

        validateSettings(min, FakeExecutableFileProbe(), FakeDirectoryProbe()) shouldBe SettingsValidationResult.Valid(min)
        validateSettings(max, FakeExecutableFileProbe(), FakeDirectoryProbe()) shouldBe SettingsValidationResult.Valid(max)
    }
}
