package dev.acme.adbtoolbox.adapters.jvm.capture

import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.domain.settings.SettingsState
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class MutableCaptureSettingsRepository(
    var state: SettingsState,
) : SettingsRepository {
    override suspend fun readSettings(): SettingsState = state

    override suspend fun writeSettings(state: SettingsState) {
        this.state = state
    }
}

class SettingsBackedCaptureDestinationTest {

    private fun tempDir(): Path = Files.createTempDirectory("settings-capture-destination-test")

    @Test
    fun `each new capture uses the latest configured directory`() = runTest {
        val firstDirectory = tempDir()
        val secondDirectory = tempDir()
        val repository = MutableCaptureSettingsRepository(
            SettingsState(captureDirectory = firstDirectory.toString()),
        )
        val destination = SettingsBackedCaptureDestination(repository)

        val first = destination.beginCapture("first.png")
            .also { it.sink.write(byteArrayOf(1)) }
            .commit()
        repository.state = SettingsState(captureDirectory = secondDirectory.toString())
        val second = destination.beginCapture("second.png")
            .also { it.sink.write(byteArrayOf(2)) }
            .commit()

        first.displayPath shouldBe firstDirectory.resolve("first.png").toString()
        second.displayPath shouldBe secondDirectory.resolve("second.png").toString()
    }

    @Test
    fun `null configured directory uses the Desktop default`() = runTest {
        val defaultDirectory = tempDir()
        val repository = MutableCaptureSettingsRepository(SettingsState(captureDirectory = null))
        val destination = SettingsBackedCaptureDestination(repository, defaultDirectory)

        val location = destination.beginCapture("screen.png")
            .also { it.sink.write(byteArrayOf(3)) }
            .commit()

        location.displayPath shouldBe defaultDirectory.resolve("screen.png").toString()
    }

    @Test
    fun `configured destination retains atomic visibility and collision handling`() = runTest {
        val configuredDirectory = tempDir()
        Files.write(configuredDirectory.resolve("screen.png"), byteArrayOf(9))
        val repository = MutableCaptureSettingsRepository(
            SettingsState(captureDirectory = configuredDirectory.toString()),
        )
        val destination = SettingsBackedCaptureDestination(repository)

        val target = destination.beginCapture("screen.png")
        target.sink.write(byteArrayOf(4, 5))
        Files.exists(configuredDirectory.resolve("screen-1.png")) shouldBe false
        val location = target.commit()

        location.displayPath shouldBe configuredDirectory.resolve("screen-1.png").toString()
        Files.readAllBytes(configuredDirectory.resolve("screen.png")) shouldBe byteArrayOf(9)
        Files.readAllBytes(configuredDirectory.resolve("screen-1.png")) shouldBe byteArrayOf(4, 5)
    }
}
