package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.Studio
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The MCP server (ADR 0015, task 066) as an agent uses it: Full control in Settings, then real
 * Streamable-HTTP calls against the emulator — an annotated screenshot, a tap that returns the next
 * screen — and the tool window's skill tip.
 */
class McpE2ETest : E2eTest() {

    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun turnOff() {
        studio.closeDialogs()
        plugin("s.setMcpAccess(java.lang.Enum.valueOf(java.lang.Class.forName('dev.acme.adbtoolbox.application.mcp.McpAccess', true, cl), 'Off')); s.setSkillTipDismissed(false); ''")
        plugin("var svc = app.getService(java.lang.Class.forName('dev.acme.adbtoolbox.intellij.mcp.McpServerService', true, cl)); svc.applyAccess(); ''")
        Adb.shell("input keyevent 3")
    }

    @Test
    fun `an agent reads a marked screenshot and taps with the next screen in the same call`() {
        enableFullControl()
        Adb.shell("am start -W -a android.settings.SETTINGS")
        val session = initialize()

        val shot = call(session, "screenshot", """{"annotate":true}""")
        shot shouldContain "\"type\":\"image\""
        shot shouldContain "Numbered boxes are get_ui refs"

        val tap = call(session, "tap", """{"text":"Display","return_ui":true}""")
        tap shouldContain "Tapped"
        tap shouldContain "Screen "
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "the Display settings screen") {
            "Brightness" in call(session, "get_ui", """{"query":"Brightness"}""")
        }
    }

    @Test
    fun `the skill tip shows while the server runs and never comes back once closed`() {
        assumeFalse(skillInstalledAnywhere(), "an agent skill is already installed for this user; the tip is hidden by design")
        enableFullControl()

        awaitUntil(Duration.ofSeconds(15), Duration.ofMillis(300), "the skill tip") {
            studio.visibleTexts().any { it.startsWith("Tip: install the Device Cockpit skill") }
        }
        studio.toolWindow().find(ComponentFixture::class.java, byXpath("//div[@accessiblename='Hide tip']")).click()

        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "the tip to go away") {
            studio.visibleTexts().none { it.startsWith("Tip: install the Device Cockpit skill") }
        }
        plugin("s.getSkillTipDismissed() + ''") shouldBe "true"
    }

    private fun enableFullControl() {
        studio.robot.runJs(
            Studio.PROJECT + """
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(function() {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "Device Cockpit");
            });
            """.trimIndent(),
            false,
        )
        val dialog = studio.dialog("Settings")
        val full = byXpath("//div[@class='JBRadioButton' and @text='Full control']")
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "the AI agents section") {
            dialog.findAll(ContainerFixture::class.java, full).any { it.isShowing }
        }
        dialog.find(ComponentFixture::class.java, full).click()
        studio.dialogButton(dialog, "OK").click()
        awaitUntil(Duration.ofSeconds(15), Duration.ofMillis(300), "the MCP server to listen") { port() > 0 && listening() }
    }

    private fun port(): Int = plugin("s.getMcpPort() + ''").toInt()

    private fun listening(): Boolean = runCatching {
        http.send(HttpRequest.newBuilder(endpoint()).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() > 0
    }.getOrDefault(false)

    private fun endpoint() = URI("http://127.0.0.1:${port()}/mcp")

    private fun initialize(): String {
        val response = post(
            null,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"e2e"}}}""",
        )
        response.statusCode() shouldBe 200
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow()
    }

    private fun call(session: String, tool: String, arguments: String): String {
        val response = post(session, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")
        response.statusCode() shouldBe 200
        return response.body()
    }

    private fun post(session: String?, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(endpoint())
            .timeout(E2eConfig.deviceTimeout(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer ${plugin("s.mcpToken() + ''")}")
            .apply { session?.let { header("Mcp-Session-Id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun skillInstalledAnywhere(): Boolean {
        val home = File(System.getProperty("user.home"))
        return listOf(".claude", ".codex", ".gemini").any { File(home, "$it/skills/adb-toolbox/SKILL.md").exists() }
    }

    /** Runs [js] in the IDE with `app`, the plugin's class loader `cl` and its app settings `s`. */
    private fun plugin(js: String): String = studio.robot.callJs(
        """
        var app = com.intellij.openapi.application.ApplicationManager.getApplication();
        var cl = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.github.dkwasniak.adbtoolbox")).getPluginClassLoader();
        var s = app.getService(java.lang.Class.forName("dev.acme.adbtoolbox.intellij.mcp.AdbToolboxAppSettings", true, cl));
        $js
        """.trimIndent(),
        false,
    )
}
