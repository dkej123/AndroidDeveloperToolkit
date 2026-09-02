package dev.acme.adbtoolbox.domain.discovery

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ToolDiscoveryCacheTest {

    private val tool = DiscoveredTool(
        id = ToolId.Adb,
        source = ToolSource.ConfiguredPath,
        path = ToolExecutablePath.of("/opt/platform-tools/adb"),
        version = ToolVersion.of("35.0.2"),
    )

    @Test
    fun `a fresh cache has no entries`() = runTest {
        ToolDiscoveryCache().get(ToolId.Adb).shouldBeNull()
    }

    @Test
    fun `put then get returns the stored tool`() = runTest {
        val cache = ToolDiscoveryCache()

        cache.put(ToolId.Adb, tool)

        cache.get(ToolId.Adb) shouldBe tool
    }

    @Test
    fun `invalidate removes only the targeted entry`() = runTest {
        val cache = ToolDiscoveryCache()
        val scrcpy = tool.copy(id = ToolId.Scrcpy)
        cache.put(ToolId.Adb, tool)
        cache.put(ToolId.Scrcpy, scrcpy)

        cache.invalidate(ToolId.Adb)

        cache.get(ToolId.Adb).shouldBeNull()
        cache.get(ToolId.Scrcpy) shouldBe scrcpy
    }

    @Test
    fun `invalidateAll clears every entry`() = runTest {
        val cache = ToolDiscoveryCache()
        cache.put(ToolId.Adb, tool)
        cache.put(ToolId.Scrcpy, tool.copy(id = ToolId.Scrcpy))

        cache.invalidateAll()

        cache.get(ToolId.Adb).shouldBeNull()
        cache.get(ToolId.Scrcpy).shouldBeNull()
    }
}
