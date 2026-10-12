package io.github.dkej123.devicecockpit.intellij.wifi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import io.github.dkej123.devicecockpit.intellij.icons.DeviceCockpitIcons
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme

/**
 * [WifiPairingDialog] itself wraps [com.intellij.openapi.ui.DialogWrapper] (untested directly, the
 * same established boundary as [io.github.dkej123.devicecockpit.intellij.apps.AppsConfirmationDialog] and
 * [io.github.dkej123.devicecockpit.intellij.ui.mirroring.MirroringOptionsDialog]); [styleAddressField] and
 * [createPairingHeaderLabel] are the bits of its presentation pulled out into plain, directly
 * unit-testable functions.
 */
class WifiPairingDialogStylingTest : BasePlatformTestCase() {

    fun `test address fields use the design system's mono font for the IP colon port values`() {
        val field = JBTextField()

        styleAddressField(field)

        assertEquals(DeviceCockpitTheme.Typography.mono, field.font)
    }

    fun `test the header label carries the supplied authorize icon and task 039 entry point copy`() {
        val header = createPairingHeaderLabel()

        assertEquals("Pair device over Wi-Fi", header.text)
        assertEquals(DeviceCockpitIcons.Actions.authorize, header.icon)
        assertEquals(DeviceCockpitTheme.Typography.sectionTitle, header.font)
    }
}
