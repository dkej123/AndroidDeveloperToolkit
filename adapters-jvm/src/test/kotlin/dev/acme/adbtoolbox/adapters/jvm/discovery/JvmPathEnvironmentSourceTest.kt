package dev.acme.adbtoolbox.adapters.jvm.discovery

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class JvmPathEnvironmentSourceTest {

    @Test
    fun `splits on the given separator and drops blank segments`() {
        splitPathVariable("/usr/bin:/bin::/opt/tools", ':') shouldBe listOf("/usr/bin", "/bin", "/opt/tools")
    }

    @Test
    fun `returns an empty list for a null or blank PATH`() {
        splitPathVariable(null, ':') shouldBe emptyList()
        splitPathVariable("", ':') shouldBe emptyList()
    }

    @Test
    fun `directories delegates to the injected PATH provider and separator`() = runTest {
        val source = JvmPathEnvironmentSource(
            pathVariableProvider = { "/usr/bin;/bin" },
            pathSeparator = ';',
        )

        source.directories() shouldBe listOf("/usr/bin", "/bin")
    }
}
