package dev.acme.adbtoolbox.intellij.mcp

import java.nio.file.Files
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillTipBannerTest {
    private val home = Files.createTempDirectory("tip-home")
    private val installer = AgentSkillInstaller(home, { null }, { "skill" })
    private val banner = SkillTipBanner(installer, onDismiss = {}, onOpenSettings = {}, runIo = { it() })

    @Test
    fun `install for Claude Code writes the skill and keeps the result visible`() {
        assertEquals(false, banner.isVisible)
        SwingUtilities.invokeAndWait {
            banner.render(true)
            assertEquals(true, banner.isVisible)
            banner.clickInstallForTest()
        }
        SwingUtilities.invokeAndWait { }

        assertEquals("skill", Files.readString(home.resolve(".claude/skills/adb-toolbox/SKILL.md")))
        assertTrue(banner.messageForTest.startsWith("Skill installed for Claude Code in "), banner.messageForTest)
        SwingUtilities.invokeAndWait { banner.render(false) }
        assertEquals(true, banner.isVisible)
    }
}
