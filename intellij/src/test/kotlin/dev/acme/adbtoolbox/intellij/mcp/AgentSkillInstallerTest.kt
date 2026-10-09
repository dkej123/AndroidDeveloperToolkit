package dev.acme.adbtoolbox.intellij.mcp

import dev.acme.adbtoolbox.application.mcp.McpAccess
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSkillInstallerTest {
    private val home = Files.createTempDirectory("skill-home")
    private var content = "v1"
    private val env = mutableMapOf<String, String>()
    private val installer = AgentSkillInstaller(home, { env[it] }, { content })

    @Test
    fun `installs per agent and reports not installed, current and outdated`() {
        assertEquals(SkillState.NotInstalled, installer.state(SkillAgent.ClaudeCode))

        assertEquals(home.resolve(".claude/skills/adb-toolbox/SKILL.md"), installer.install(SkillAgent.ClaudeCode))
        assertEquals("v1", Files.readString(home.resolve(".claude/skills/adb-toolbox/SKILL.md")))
        assertEquals(SkillState.Current, installer.state(SkillAgent.ClaudeCode))
        assertEquals(true, installer.anyInstalled())

        content = "v2"
        assertEquals(SkillState.Outdated, installer.state(SkillAgent.ClaudeCode))
        assertEquals(SkillState.NotInstalled, installer.state(SkillAgent.Gemini))
        assertEquals(home.resolve(".gemini/skills/adb-toolbox/SKILL.md"), installer.install(SkillAgent.Gemini))
    }

    @Test
    fun `agent home directories follow their environment overrides`() {
        env["CLAUDE_CONFIG_DIR"] = home.resolve("cc").toString()
        env["CODEX_HOME"] = home.resolve("cx").toString()

        assertEquals(home.resolve("cc/skills/adb-toolbox/SKILL.md"), installer.file(SkillAgent.ClaudeCode))
        assertEquals(home.resolve("cx/skills/adb-toolbox/SKILL.md"), installer.file(SkillAgent.Codex))
        assertEquals(false, installer.anyInstalled())
    }

    @Test
    fun `the tip shows while the server is on, nothing is installed and it was not dismissed`() {
        assertEquals(true, showSkillTip(McpAccess.ReadOnly, dismissed = false, installed = false))
        assertEquals(false, showSkillTip(McpAccess.Off, dismissed = false, installed = false))
        assertEquals(false, showSkillTip(McpAccess.FullControl, dismissed = true, installed = false))
        assertEquals(false, showSkillTip(McpAccess.FullControl, dismissed = false, installed = true))
    }
}
