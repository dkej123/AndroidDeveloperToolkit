package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.OperatingSystem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultSdkLocationPlatformToolsSourceTest {

    private fun source(
        os: OperatingSystem,
        home: String? = "/Users/dev",
        environment: Map<String, String> = emptyMap(),
    ) = DefaultSdkLocationPlatformToolsSource(
        hostPlatform = { os },
        userHome = { home },
        environmentProvider = environment::get,
    )

    @Test
    fun `macOS uses the Android Studio default SDK under Library`() = runTest {
        source(OperatingSystem.MacOs).platformToolsDirectory() shouldBe "/Users/dev/Library/Android/sdk/platform-tools"
    }

    @Test
    fun `Linux uses the Android Studio default SDK under the home directory`() = runTest {
        source(OperatingSystem.Linux, home = "/home/dev").platformToolsDirectory() shouldBe
            "/home/dev/Android/Sdk/platform-tools"
    }

    @Test
    fun `Windows uses the Android Studio default SDK under LOCALAPPDATA`() = runTest {
        source(OperatingSystem.Windows, environment = mapOf("LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local"))
            .platformToolsDirectory() shouldBe "C:\\Users\\dev\\AppData\\Local/Android/Sdk/platform-tools"
    }

    @Test
    fun `returns null when the base directory is unknown`() = runTest {
        source(OperatingSystem.MacOs, home = null).platformToolsDirectory().shouldBeNull()
        source(OperatingSystem.Windows).platformToolsDirectory().shouldBeNull()
    }
}
