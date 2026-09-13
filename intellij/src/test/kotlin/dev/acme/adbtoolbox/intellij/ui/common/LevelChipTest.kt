package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import com.intellij.util.ui.JBUI
import java.awt.Dimension

class LevelChipTest : BasePlatformTestCase() {

    fun `test level chip is the supplied square control and exposes its severity`() {
        val chip = LevelChip(LogSeverity.WARN)

        assertEquals("W", chip.text)
        assertEquals(LogSeverity.WARN, chip.level)
        assertEquals(Dimension(JBUI.scale(20), JBUI.scale(20)), chip.preferredSize)
        assertEquals("Minimum Logcat level: W", chip.accessibleContext.accessibleName)
    }

    fun `test selected level chip remains a toggle rather than a command button`() {
        val chip = LevelChip(LogSeverity.ERROR)

        chip.doClick()

        assertTrue(chip.isSelected)
        assertEquals(AdbToolboxTheme.Colors.accentBg, chip.background)
        assertEquals(AdbToolboxTheme.LogSeverityColors.error.level, chip.foreground)
    }

    fun `test assert is not offered by the supplied minimum level row`() {
        try {
            LevelChip(LogSeverity.ASSERT)
            fail("expected ASSERT to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
