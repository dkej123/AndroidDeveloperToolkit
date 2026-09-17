package dev.acme.adbtoolbox.intellij.wifi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme

/**
 * [WifiPairingDialog] itself wraps [com.intellij.openapi.ui.DialogWrapper] (untested directly, the
 * same established boundary as [dev.acme.adbtoolbox.intellij.apps.AppsConfirmationDialog] and
 * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsDialog]); [styleAddressField] and
 * [createPairingHeaderLabel] are the bits of its presentation pulled out into plain, directly
 * unit-testable functions.
 */
class WifiPairingDialogStylingTest : BasePlatformTestCase() {

    fun `test address fields use the design system's mono font for the IP colon port values`() {
        val field = JBTextField()

        styleAddressField(field)

        assertEquals(AdbToolboxTheme.Typography.mono, field.font)
    }

    fun `test the header label carries the supplied authorize icon and task 039 entry point copy`() {
        val header = createPairingHeaderLabel()

        assertEquals("Pair device over Wi-Fi", header.text)
        assertEquals(AdbToolboxIcons.Actions.authorize, header.icon)
        assertEquals(AdbToolboxTheme.Typography.sectionTitle, header.font)
    }
}
