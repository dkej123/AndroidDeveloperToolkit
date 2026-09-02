package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.ToolId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InMemoryConfiguredToolPathSourceTest {

    @Test
    fun `an unset tool has no configured path`() = runTest {
        InMemoryConfiguredToolPathSource().configuredPath(ToolId.Adb).shouldBeNull()
    }

    @Test
    fun `setConfiguredPath is readable back by the same tool id`() = runTest {
        val source = InMemoryConfiguredToolPathSource()

        source.setConfiguredPath(ToolId.Adb, "/opt/tools/adb")

        source.configuredPath(ToolId.Adb) shouldBe "/opt/tools/adb"
        source.configuredPath(ToolId.Scrcpy).shouldBeNull()
    }

    @Test
    fun `setting null clears a previously configured path`() = runTest {
        val source = InMemoryConfiguredToolPathSource()
        source.setConfiguredPath(ToolId.Adb, "/opt/tools/adb")

        source.setConfiguredPath(ToolId.Adb, null)

        source.configuredPath(ToolId.Adb).shouldBeNull()
    }
}
