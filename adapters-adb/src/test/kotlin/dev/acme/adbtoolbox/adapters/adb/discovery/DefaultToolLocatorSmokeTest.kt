package dev.acme.adbtoolbox.adapters.adb.discovery

import dev.acme.adbtoolbox.adapters.jvm.discovery.EnvironmentAndroidSdkPlatformToolsSource
import dev.acme.adbtoolbox.adapters.jvm.discovery.InMemoryConfiguredToolPathSource
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmExecutableFileProbe
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmHostPlatformProvider
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmPathEnvironmentSource
import dev.acme.adbtoolbox.adapters.jvm.process.JvmProcessExecutor
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.ToolId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * A real-environment integration check: wires every production discovery adapter together and
 * resolves `adb` from whatever this machine actually has (configured/SDK-env/PATH), asserting only
 * that discovery completes without throwing and, when it finds something, that the reported version
 * string is non-blank. Disabled by default — normal `test`/`koverVerify` runs use fakes only
 * (`DefaultToolLocatorTest`), per `tasks/README.md`'s "real-device/tool scenarios are opt-in smoke
 * tests only" rule.
 *
 * Enable explicitly with: `./gradlew :adapters-adb:test -DadbToolbox.discoverySmokeTest=true`
 */
@EnabledIfSystemProperty(named = "adbToolbox.discoverySmokeTest", matches = "true")
class DefaultToolLocatorSmokeTest {

    @Test
    fun `resolves adb from this machine's real configuration`() = runTest {
        val locator = DefaultToolLocator(
            configuredPathSource = InMemoryConfiguredToolPathSource(),
            androidSdkSources = listOf(EnvironmentAndroidSdkPlatformToolsSource()),
            pathEnvironmentSource = JvmPathEnvironmentSource(),
            executableProbe = JvmExecutableFileProbe(),
            hostPlatformProvider = JvmHostPlatformProvider(),
            processExecutor = JvmProcessExecutor(),
        )

        when (val outcome = locator.locate(ToolId.Adb)) {
            is DiscoveryOutcome.Found -> check(outcome.tool.version.raw.isNotBlank()) {
                "adb was found but reported a blank version"
            }

            is DiscoveryOutcome.Failed -> println(
                "adb not resolvable on this machine (expected in CI/sandboxes without adb installed): " +
                    outcome.error,
            )
        }
    }
}
