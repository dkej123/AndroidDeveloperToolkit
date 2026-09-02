package dev.acme.adbtoolbox.adapters.jvm.discovery

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class EnvironmentAndroidSdkPlatformToolsSourceTest {

    @Test
    fun `resolves platform-tools under ANDROID_HOME`() = runTest {
        val source = EnvironmentAndroidSdkPlatformToolsSource(
            environmentProvider = { name -> if (name == "ANDROID_HOME") "/opt/android-sdk" else null },
        )

        source.platformToolsDirectory() shouldBe "/opt/android-sdk/platform-tools"
    }

    @Test
    fun `falls back to ANDROID_SDK_ROOT when ANDROID_HOME is unset`() = runTest {
        val source = EnvironmentAndroidSdkPlatformToolsSource(
            environmentProvider = { name -> if (name == "ANDROID_SDK_ROOT") "/opt/sdk-root" else null },
        )

        source.platformToolsDirectory() shouldBe "/opt/sdk-root/platform-tools"
    }

    @Test
    fun `returns null when neither environment variable is set`() = runTest {
        val source = EnvironmentAndroidSdkPlatformToolsSource(environmentProvider = { null })

        source.platformToolsDirectory().shouldBeNull()
    }

    @Test
    fun `trims a trailing separator before appending platform-tools`() = runTest {
        val source = EnvironmentAndroidSdkPlatformToolsSource(
            environmentProvider = { name -> if (name == "ANDROID_HOME") "/opt/android-sdk/" else null },
        )

        source.platformToolsDirectory() shouldBe "/opt/android-sdk/platform-tools"
    }
}
