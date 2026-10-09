package dev.acme.adbtoolbox.application.mcp

import dev.acme.adbtoolbox.application.mcp.tools.mcpCatalog
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class AgentSkillTest {
    private val skill = agentSkill(mcpCatalog())

    @Test
    fun `frontmatter follows the Agent Skills format within the strictest agent's limits`() {
        skill shouldStartWith "---\nname: adb-toolbox\ndescription: "
        val description = skill.lines()[2].removePrefix("description: ")
        description.length shouldBeLessThanOrEqual 500
        description shouldContain "adb-toolbox"
        skill.lines()[3] shouldBe "---"
    }

    @Test
    fun `every tool is listed with its level and the workflow names the cheap paths`() {
        mcpCatalog().forEach { skill shouldContain "`${it.name}`" }
        skill shouldContain "`manage_app` (full control, asks first)"
        skill shouldContain "return_ui=true"
        skill shouldContain "annotate=true"
    }
}
