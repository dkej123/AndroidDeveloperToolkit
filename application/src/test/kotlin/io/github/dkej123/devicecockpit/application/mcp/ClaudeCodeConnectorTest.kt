package io.github.dkej123.devicecockpit.application.mcp

import io.github.dkej123.devicecockpit.domain.process.FakeProcessExecutor
import io.github.dkej123.devicecockpit.domain.process.ProcessEvent
import io.github.dkej123.devicecockpit.domain.process.ProcessOutcome
import io.github.dkej123.devicecockpit.domain.process.ProcessRequest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private const val URL = "http://127.0.0.1:60996/mcp"
private const val CLAUDE = "/Users/me/.local/bin/claude"

private fun ProcessRequest.argv() = command.arguments.joinToString(" ")

private fun done(code: Int, vararg out: String): List<ProcessEvent> =
    out.map { ProcessEvent.StdoutText(it) } + ProcessEvent.Completed(ProcessOutcome.Completed(code))

class ClaudeCodeConnectorTest {

    @Test
    fun `the claude CLI is found on PATH or in its usual install folders`() {
        val present = setOf("/opt/homebrew/bin/claude", "/Users/me/.local/bin/claude")
        findClaudeCli("/Users/me", "/usr/bin:/opt/homebrew/bin", windows = false, exists = present::contains) shouldBe "/opt/homebrew/bin/claude"
        findClaudeCli("/Users/me", "/usr/bin", windows = false, exists = present::contains) shouldBe "/Users/me/.local/bin/claude"
        findClaudeCli("/Users/me", null, windows = false, exists = { false }) shouldBe null
    }

    @Test
    fun `connecting re-registers the server as device-cockpit in user scope`() = runTest {
        val processes = FakeProcessExecutor { request ->
            when {
                request.argv().startsWith("mcp get adb-toolbox") -> done(1)
                else -> done(0)
            }
        }

        ClaudeCodeConnector(processes) { CLAUDE }.connect(URL, "atk_secret") shouldBe ClaudeCodeConnection.Connected

        processes.requests.map { it.command.executable }.toSet() shouldBe setOf(CLAUDE)
        processes.requests.map { it.argv() } shouldBe listOf(
            "mcp get adb-toolbox",
            "mcp remove device-cockpit -s user",
            "mcp add --transport http --scope user device-cockpit $URL --header Authorization: Bearer atk_secret",
        )
    }

    @Test
    fun `an old adb-toolbox registration of this server is removed, someone else's is kept`() = runTest {
        var oldUrl = URL
        val processes = FakeProcessExecutor { request ->
            if (request.argv().startsWith("mcp get adb-toolbox")) done(0, "adb-toolbox:", "  Type: http", "  URL: $oldUrl") else done(0)
        }
        val connector = ClaudeCodeConnector(processes) { CLAUDE }

        connector.connect(URL, "atk_secret")
        processes.requests.any { it.argv() == "mcp remove adb-toolbox -s user" } shouldBe true

        oldUrl = "https://example.com/adb-toolbox"
        val before = processes.requests.size
        connector.connect(URL, "atk_secret")
        processes.requests.drop(before).any { it.argv().startsWith("mcp remove adb-toolbox") } shouldBe false
    }

    @Test
    fun `a missing CLI or a failed add is reported`() = runTest {
        ClaudeCodeConnector(FakeProcessExecutor { done(0) }) { null }.connect(URL, "t") shouldBe ClaudeCodeConnection.CliNotFound

        val failing = FakeProcessExecutor { request ->
            if (request.argv().startsWith("mcp add")) listOf(ProcessEvent.StderrText("boom"), ProcessEvent.Completed(ProcessOutcome.Completed(1))) else done(1)
        }
        ClaudeCodeConnector(failing) { CLAUDE }.connect(URL, "t") shouldBe ClaudeCodeConnection.Failed("boom")
    }
}
