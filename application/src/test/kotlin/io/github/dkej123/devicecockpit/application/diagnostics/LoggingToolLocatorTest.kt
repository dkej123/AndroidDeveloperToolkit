package io.github.dkej123.devicecockpit.application.diagnostics

import io.github.dkej123.devicecockpit.domain.diagnostics.DiagCategory
import io.github.dkej123.devicecockpit.domain.diagnostics.DiagLevel
import io.github.dkej123.devicecockpit.domain.diagnostics.RecordingDiagnosticsLog
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveredTool
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolExecutablePath
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LoggingToolLocatorTest {

    private val log = RecordingDiagnosticsLog()
    private val adb = DiscoveredTool(ToolId.Adb, ToolSource.AndroidSdk, ToolExecutablePath.of("/sdk/platform-tools/adb"), ToolVersion.of("35.0.2"))

    @Test
    fun `a resolved tool is logged once at info with source path and version, repeats only at debug`() = runTest {
        val locator = LoggingToolLocator(FakeToolLocator { DiscoveryOutcome.Found(adb) }, log)

        locator.locate(ToolId.Adb) shouldBe DiscoveryOutcome.Found(adb)
        locator.locate(ToolId.Adb)

        val entries = log.inCategory(DiagCategory.DISCOVERY)
        entries.count { it.level == DiagLevel.INFO } shouldBe 1
        entries.first().fields["path"] shouldBe "/sdk/platform-tools/adb"
        entries.first().fields["version"] shouldBe "35.0.2"
    }

    @Test
    fun `a discovery failure is a warning describing what was tried`() = runTest {
        val locator = LoggingToolLocator(
            FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, listOf(ToolSource.PathFallback))) },
            log,
        )

        locator.locate(ToolId.Scrcpy)

        val entry = log.inCategory(DiagCategory.DISCOVERY).single()
        entry.level shouldBe DiagLevel.WARN
        entry.fields["tool"] shouldBe "Scrcpy"
    }
}
