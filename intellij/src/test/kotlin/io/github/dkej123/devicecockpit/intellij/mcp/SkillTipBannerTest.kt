package io.github.dkej123.devicecockpit.intellij.mcp

import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkillTipBannerTest {
    private var connects = 0
    private var dismissed = 0
    private val banner = SkillTipBanner(
        connect = { report -> connects++; report("Connected as device-cockpit.") },
        onDismiss = { dismissed++ },
        onOpenSettings = {},
    )

    @Test
    fun `one click turns agents on, keeps the result visible and does not come back`() {
        assertEquals(false, banner.isVisible)
        SwingUtilities.invokeAndWait {
            banner.render(true, serverOn = false)
            assertEquals(true, banner.isVisible)
            assertEquals("Turn on and connect Claude Code", banner.connectTextForTest)
            banner.clickConnectForTest()
        }

        assertEquals(1, connects)
        assertEquals("Connected as device-cockpit.", banner.messageForTest)
        assertEquals(1, dismissed)
        SwingUtilities.invokeAndWait { banner.render(false, serverOn = true) }
        assertEquals(true, banner.isVisible)
    }

    @Test
    fun `with the server already on the link only connects`() {
        SwingUtilities.invokeAndWait { banner.render(true, serverOn = true) }

        assertEquals("Connect Claude Code", banner.connectTextForTest)
    }
}
