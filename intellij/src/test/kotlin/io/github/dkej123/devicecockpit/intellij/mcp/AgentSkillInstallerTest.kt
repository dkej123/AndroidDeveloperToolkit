package io.github.dkej123.devicecockpit.intellij.mcp

import io.github.dkej123.devicecockpit.application.mcp.McpAccess
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

        assertEquals(home.resolve(".claude/skills/device-cockpit/SKILL.md"), installer.install(SkillAgent.ClaudeCode))
        assertEquals("v1", Files.readString(home.resolve(".claude/skills/device-cockpit/SKILL.md")))
        assertEquals(SkillState.Current, installer.state(SkillAgent.ClaudeCode))
        assertEquals(true, installer.anyInstalled())

        content = "v2"
        assertEquals(SkillState.Outdated, installer.state(SkillAgent.ClaudeCode))
        assertEquals(SkillState.NotInstalled, installer.state(SkillAgent.Gemini))
        assertEquals(home.resolve(".gemini/skills/device-cockpit/SKILL.md"), installer.install(SkillAgent.Gemini))
    }

    @Test
    fun `installing removes the skill earlier versions installed as adb-toolbox, and only ours`() {
        val old = Files.createDirectories(home.resolve(".claude/skills/adb-toolbox")).resolve("SKILL.md")
        Files.writeString(old, "---\nname: adb-toolbox\ndescription: old\n---\n")
        val foreign = Files.createDirectories(home.resolve(".codex/skills/adb-toolbox")).resolve("SKILL.md")
        Files.writeString(foreign, "---\nname: someone-elses\n---\n")

        installer.install(SkillAgent.ClaudeCode)
        installer.install(SkillAgent.Codex)

        assertEquals(false, Files.exists(old.parent))
        assertEquals(true, Files.exists(foreign))
    }

    @Test
    fun `agent home directories follow their environment overrides`() {
        env["CLAUDE_CONFIG_DIR"] = home.resolve("cc").toString()
        env["CODEX_HOME"] = home.resolve("cx").toString()

        assertEquals(home.resolve("cc/skills/device-cockpit/SKILL.md"), installer.file(SkillAgent.ClaudeCode))
        assertEquals(home.resolve("cx/skills/device-cockpit/SKILL.md"), installer.file(SkillAgent.Codex))
        assertEquals(false, installer.anyInstalled())
    }

    @Test
    fun `the tip shows until agents are set up or it is dismissed — also on a first run with the server off`() {
        assertEquals(true, showSkillTip(McpAccess.Off, dismissed = false, installed = false))
        assertEquals(true, showSkillTip(McpAccess.Off, dismissed = false, installed = true))
        assertEquals(true, showSkillTip(McpAccess.ReadOnly, dismissed = false, installed = false))
        assertEquals(false, showSkillTip(McpAccess.FullControl, dismissed = false, installed = true))
        assertEquals(false, showSkillTip(McpAccess.Off, dismissed = true, installed = false))
    }
}
