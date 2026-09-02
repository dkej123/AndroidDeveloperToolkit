package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.OperatingSystem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JvmHostPlatformProviderTest {

    @Test
    fun `recognizes Windows os-name values`() {
        mapOsName("Windows 11") shouldBe OperatingSystem.Windows
    }

    @Test
    fun `recognizes macOS os-name values`() {
        mapOsName("Mac OS X") shouldBe OperatingSystem.MacOs
        mapOsName("Darwin") shouldBe OperatingSystem.MacOs
    }

    @Test
    fun `falls back to Linux for anything else`() {
        mapOsName("Linux") shouldBe OperatingSystem.Linux
        mapOsName("SunOS") shouldBe OperatingSystem.Linux
    }

    @Test
    fun `current delegates to the injected os-name provider`() {
        val provider = JvmHostPlatformProvider(osNameProvider = { "Windows 10" })

        provider.current() shouldBe OperatingSystem.Windows
    }
}
