package dev.acme.adbtoolbox.adapters.jvm.discovery

import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.domain.settings.SettingsState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class FakeSettingsRepository(private var state: SettingsState) : SettingsRepository {
    override suspend fun readSettings(): SettingsState = state
    override suspend fun writeSettings(state: SettingsState) {
        this.state = state
    }
}

class SettingsBackedConfiguredToolPathSourceTest {

    @Test
    fun `no override configured returns null for both tools`() = runTest {
        val source = SettingsBackedConfiguredToolPathSource(FakeSettingsRepository(SettingsState.DEFAULT))

        source.configuredPath(ToolId.Adb).shouldBeNull()
        source.configuredPath(ToolId.Scrcpy).shouldBeNull()
    }

    @Test
    fun `blank persisted overrides are exposed as no configured path`() = runTest {
        val repository = FakeSettingsRepository(
            SettingsState(adbPathOverride = "   ", scrcpyPathOverride = "\t"),
        )
        val source = SettingsBackedConfiguredToolPathSource(repository)

        source.configuredPath(ToolId.Adb).shouldBeNull()
        source.configuredPath(ToolId.Scrcpy).shouldBeNull()
    }

    @Test
    fun `persisted overrides are trimmed before discovery consumes them`() = runTest {
        val repository = FakeSettingsRepository(
            SettingsState(adbPathOverride = "  /opt/tools/adb\n", scrcpyPathOverride = "\t/opt/tools/scrcpy "),
        )
        val source = SettingsBackedConfiguredToolPathSource(repository)

        source.configuredPath(ToolId.Adb) shouldBe "/opt/tools/adb"
        source.configuredPath(ToolId.Scrcpy) shouldBe "/opt/tools/scrcpy"
    }

    @Test
    fun `an adb override is returned only for the adb tool id`() = runTest {
        val repository = FakeSettingsRepository(SettingsState(adbPathOverride = "/opt/tools/adb"))
        val source = SettingsBackedConfiguredToolPathSource(repository)

        source.configuredPath(ToolId.Adb) shouldBe "/opt/tools/adb"
        source.configuredPath(ToolId.Scrcpy).shouldBeNull()
    }

    @Test
    fun `a scrcpy override is returned only for the scrcpy tool id`() = runTest {
        val repository = FakeSettingsRepository(SettingsState(scrcpyPathOverride = "/opt/tools/scrcpy"))
        val source = SettingsBackedConfiguredToolPathSource(repository)

        source.configuredPath(ToolId.Scrcpy) shouldBe "/opt/tools/scrcpy"
        source.configuredPath(ToolId.Adb).shouldBeNull()
    }

    @Test
    fun `a later settings write is reflected on the next read`() = runTest {
        val repository = FakeSettingsRepository(SettingsState.DEFAULT)
        val source = SettingsBackedConfiguredToolPathSource(repository)

        repository.writeSettings(SettingsState(adbPathOverride = "/new/adb"))

        source.configuredPath(ToolId.Adb) shouldBe "/new/adb"
    }
}
