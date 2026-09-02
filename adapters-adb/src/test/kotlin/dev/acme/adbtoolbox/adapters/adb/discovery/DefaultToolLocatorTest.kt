package dev.acme.adbtoolbox.adapters.adb.discovery

import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeAndroidSdkPlatformToolsSource
import dev.acme.adbtoolbox.domain.discovery.FakeConfiguredToolPathSource
import dev.acme.adbtoolbox.domain.discovery.FakeExecutableFileProbe
import dev.acme.adbtoolbox.domain.discovery.FakeHostPlatformProvider
import dev.acme.adbtoolbox.domain.discovery.FakePathEnvironmentSource
import dev.acme.adbtoolbox.domain.discovery.OperatingSystem
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

private const val ADB_VERSION_OUTPUT = "Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458"

class DefaultToolLocatorTest {

    private class Fixture(
        val configuredPathSource: FakeConfiguredToolPathSource = FakeConfiguredToolPathSource(),
        val sdkSource: FakeAndroidSdkPlatformToolsSource = FakeAndroidSdkPlatformToolsSource(),
        val pathEnvironmentSource: FakePathEnvironmentSource = FakePathEnvironmentSource(),
        val executableProbe: FakeExecutableFileProbe = FakeExecutableFileProbe(),
        val hostPlatformProvider: FakeHostPlatformProvider = FakeHostPlatformProvider(OperatingSystem.Linux),
        val processExecutor: FakeProcessExecutor = FakeProcessExecutor {
            listOf(
                ProcessEvent.StdoutText(ADB_VERSION_OUTPUT),
                ProcessEvent.Completed(ProcessOutcome.Completed(0)),
            )
        },
    ) {
        fun locator(processExecutor: ProcessExecutor = this.processExecutor) = DefaultToolLocator(
            configuredPathSource = configuredPathSource,
            androidSdkSources = listOf(sdkSource),
            pathEnvironmentSource = pathEnvironmentSource,
            executableProbe = executableProbe,
            hostPlatformProvider = hostPlatformProvider,
            processExecutor = processExecutor,
        )
    }

    @Test
    fun `resolves via the configured path when one is set and valid`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val found = outcome.shouldBeInstanceOf<DiscoveryOutcome.Found>()
        found.tool.source shouldBe ToolSource.ConfiguredPath
        found.tool.path.value shouldBe "/custom/adb"
        found.tool.version.raw shouldBe "35.0.2-12147458"
    }

    @Test
    fun `falls back to the Android SDK tier when no path is configured`() = runTest {
        val fixture = Fixture()
        fixture.sdkSource.directory = "/sdk/platform-tools"
        fixture.executableProbe.markExecutable("/sdk/platform-tools/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val found = outcome.shouldBeInstanceOf<DiscoveryOutcome.Found>()
        found.tool.source shouldBe ToolSource.AndroidSdk
        found.tool.path.value shouldBe "/sdk/platform-tools/adb"
    }

    @Test
    fun `falls back to PATH when neither configured nor SDK tiers resolve`() = runTest {
        val fixture = Fixture()
        fixture.pathEnvironmentSource.directories = listOf("/usr/local/bin", "/usr/bin")
        fixture.executableProbe.markExecutable("/usr/bin/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val found = outcome.shouldBeInstanceOf<DiscoveryOutcome.Found>()
        found.tool.source shouldBe ToolSource.PathFallback
        found.tool.path.value shouldBe "/usr/bin/adb"
    }

    @Test
    fun `reports ToolNotFound when no tier resolves anything`() = runTest {
        val outcome = Fixture().locator().locate(ToolId.Adb)

        val failed = outcome.shouldBeInstanceOf<DiscoveryOutcome.Failed>()
        failed.error.shouldBeInstanceOf<DiscoveryError.ToolNotFound>()
    }

    @Test
    fun `an invalid configured path fails immediately without falling back to SDK or PATH`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        // Not marked executable: simulates a wrong/broken configured path.
        fixture.sdkSource.directory = "/sdk/platform-tools"
        fixture.executableProbe.markExecutable("/sdk/platform-tools/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val failed = outcome.shouldBeInstanceOf<DiscoveryOutcome.Failed>()
        val error = failed.error.shouldBeInstanceOf<DiscoveryError.ExecutableInvalid>()
        error.source shouldBe ToolSource.ConfiguredPath
        error.path shouldBe "/custom/adb"
    }

    @Test
    fun `a stale configured path whose file has since disappeared also fails immediately`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")
        fixture.executableProbe.unmark("/custom/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val failed = outcome.shouldBeInstanceOf<DiscoveryOutcome.Failed>()
        failed.error.shouldBeInstanceOf<DiscoveryError.ExecutableInvalid>()
    }

    @Test
    fun `a failing version query is reported as VersionQueryFailed`() = runTest {
        val fixture = Fixture(
            processExecutor = FakeProcessExecutor {
                listOf(
                    ProcessEvent.StderrText("permission denied"),
                    ProcessEvent.Completed(ProcessOutcome.Completed(126)),
                )
            },
        )
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val failed = outcome.shouldBeInstanceOf<DiscoveryOutcome.Failed>()
        failed.error.shouldBeInstanceOf<DiscoveryError.VersionQueryFailed>()
    }

    @Test
    fun `unparseable version output is reported as VersionQueryFailed`() = runTest {
        val fixture = Fixture(
            processExecutor = FakeProcessExecutor {
                listOf(
                    ProcessEvent.StdoutText("not a version"),
                    ProcessEvent.Completed(ProcessOutcome.Completed(0)),
                )
            },
        )
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")

        val outcome = fixture.locator().locate(ToolId.Adb)

        val failed = outcome.shouldBeInstanceOf<DiscoveryOutcome.Failed>()
        failed.error.shouldBeInstanceOf<DiscoveryError.VersionQueryFailed>()
    }

    @Test
    fun `a second locate call is served from cache without re-invoking any port`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")
        val locator = fixture.locator()

        locator.locate(ToolId.Adb)
        locator.locate(ToolId.Adb)

        fixture.processExecutor.requests.size shouldBe 1
    }

    @Test
    fun `forceRefresh bypasses the cache`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")
        val locator = fixture.locator()
        locator.locate(ToolId.Adb)

        locator.locate(ToolId.Adb, forceRefresh = true)

        fixture.processExecutor.requests.size shouldBe 2
    }

    @Test
    fun `invalidate clears the cached entry so the next locate call re-resolves`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")
        val locator = fixture.locator()
        locator.locate(ToolId.Adb)

        locator.invalidate(ToolId.Adb)
        locator.locate(ToolId.Adb)

        fixture.processExecutor.requests.size shouldBe 2
    }

    @Test
    fun `cancelling the calling coroutine cancels an in-flight version query without hanging`() = runTest {
        val fixture = Fixture()
        fixture.configuredPathSource.set(ToolId.Adb, "/custom/adb")
        fixture.executableProbe.markExecutable("/custom/adb")
        val hangingExecutor = object : ProcessExecutor {
            override fun execute(request: dev.acme.adbtoolbox.domain.process.ProcessRequest): Flow<ProcessEvent> =
                flow { awaitCancellation() }
        }
        val locator = fixture.locator(processExecutor = hangingExecutor)

        val job = launch { locator.locate(ToolId.Adb) }
        yield()
        job.cancel()

        withTimeout(1000) {
            job.join()
        }
        job.isCancelled shouldBe true
    }
}
