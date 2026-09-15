package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import javax.swing.JButton

/**
 * [AppsConfirmationDialog] itself wraps [com.intellij.openapi.ui.DialogWrapper] (untested directly,
 * the same established boundary as
 * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsDialog] and
 * [dev.acme.adbtoolbox.intellij.wifi.WifiPairingDialog]); [styleDestructiveButton] is the one bit of
 * its presentation pulled out into a plain, directly unit-testable function.
 */
class AppsConfirmationDialogTest : BasePlatformTestCase() {

    fun `test the destructive button gets a red fill and white text`() {
        val button = JButton("Uninstall")

        styleDestructiveButton(button)

        assertTrue(button.isOpaque)
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.red, button.background)
        assertEquals(Color.WHITE, button.foreground)
    }
}
