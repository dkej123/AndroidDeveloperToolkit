package dev.acme.adbtoolbox.intellij.mcp

import dev.acme.adbtoolbox.application.mcp.AGENT_SKILL_NAME
import dev.acme.adbtoolbox.application.mcp.McpAccess
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Agents that read Agent Skills (`SKILL.md`) from a user-level folder (task 066). */
enum class SkillAgent(val title: String, private val homeVariable: String?, private val homeFolder: String) {
    ClaudeCode("Claude Code", "CLAUDE_CONFIG_DIR", ".claude"),
    Codex("Codex CLI", "CODEX_HOME", ".codex"),
    Gemini("Gemini CLI", null, ".gemini"),
    ;

    internal fun skillsDir(home: Path, env: (String) -> String?): Path =
        (homeVariable?.let(env)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: home.resolve(homeFolder)).resolve("skills")
}

enum class SkillState { NotInstalled, Current, Outdated }

/** Writes the ADB Toolbox skill ([content]) where each [SkillAgent] looks for user skills. */
class AgentSkillInstaller(
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val env: (String) -> String? = System::getenv,
    val content: () -> String,
) {
    fun file(agent: SkillAgent): Path = agent.skillsDir(home, env).resolve(AGENT_SKILL_NAME).resolve("SKILL.md")

    fun state(agent: SkillAgent): SkillState {
        val file = file(agent)
        if (!Files.isRegularFile(file)) return SkillState.NotInstalled
        val installed = runCatching { Files.readString(file) }.getOrNull()
        return if (installed == content()) SkillState.Current else SkillState.Outdated
    }

    fun anyInstalled(): Boolean = SkillAgent.entries.any { state(it) != SkillState.NotInstalled }

    /** Installs or updates the skill for [agent]; the file it wrote. Throws on an I/O failure. */
    fun install(agent: SkillAgent): Path {
        val file = file(agent)
        Files.createDirectories(file.parent)
        Files.writeString(file, content())
        return file
    }

    companion object {
        /** The skill generated from the server's tool catalog, installed under the user's home. */
        fun forUser(): AgentSkillInstaller = AgentSkillInstaller(content = { skill })

        private val skill by lazy {
            dev.acme.adbtoolbox.application.mcp.agentSkill(dev.acme.adbtoolbox.application.mcp.tools.mcpCatalog())
        }
    }
}

/** The tool window's skill tip: only while the server runs, nothing is installed and it was never dismissed. */
fun showSkillTip(access: McpAccess, dismissed: Boolean, installed: Boolean): Boolean =
    access != McpAccess.Off && !dismissed && !installed
