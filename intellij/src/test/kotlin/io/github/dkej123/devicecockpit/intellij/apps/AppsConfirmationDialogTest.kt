package io.github.dkej123.devicecockpit.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import javax.swing.JButton

/**
 * [AppsConfirmationDialog] itself wraps [com.intellij.openapi.ui.DialogWrapper] (untested directly,
 * the same established boundary as
 * [io.github.dkej123.devicecockpit.intellij.ui.mirroring.MirroringOptionsDialog] and
 * [io.github.dkej123.devicecockpit.intellij.wifi.WifiPairingDialog]); [styleDestructiveButton] is the one bit of
 * its presentation pulled out into a plain, directly unit-testable function.
 */
class AppsConfirmationDialogTest : BasePlatformTestCase() {

    fun `test the destructive button gets a red fill and white text through the IDE's rounded button painter`() {
        val button = JButton("Uninstall")

        styleDestructiveButton(button)

        // An opaque button would paint a square red block behind the rounded IDE button.
        assertFalse(button.isOpaque)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.red, button.getClientProperty("JButton.backgroundColor"))
        assertEquals(Color.WHITE, button.getClientProperty("JButton.textColor"))
        assertEquals(Color.WHITE, button.foreground)
    }
}
