package dev.acme.adbtoolbox.intellij.wifi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.wifi.WifiPairingInput

/**
 * [wifiPairingInputForDialogResult] is the pure seam between [WifiPairingDialog]'s raw Swing text
 * and task 039's domain-layer [WifiPairingInput] — tested directly, the same "extract the pure
 * mapper, leave the dialog construction itself untested" pattern
 * [dev.acme.adbtoolbox.intellij.apps.UninstallConfirmationPresenterTest] documents for this
 * headless sandbox.
 */
class WifiPairingInputPresenterTest : BasePlatformTestCase() {

    fun `test a confirmed result maps to Submitted with every field carried through`() {
        val result = WifiPairingDialogResult(
            confirmed = true,
            pairingAddressText = "192.168.1.42:37000",
            pairingCodeText = "123456",
            connectAddressText = "192.168.1.42:5555",
        )

        val input = wifiPairingInputForDialogResult(result)

        assertEquals(
            WifiPairingInput.Submitted("192.168.1.42:37000", "123456", "192.168.1.42:5555"),
            input,
        )
    }

    fun `test a cancelled result maps to Cancelled regardless of any typed text`() {
        val result = WifiPairingDialogResult(
            confirmed = false,
            pairingAddressText = "192.168.1.42:37000",
            pairingCodeText = "123456",
            connectAddressText = "192.168.1.42:5555",
        )

        assertEquals(WifiPairingInput.Cancelled, wifiPairingInputForDialogResult(result))
    }

    fun `test the dialog result's toString redacts the pairing code`() {
        val result = WifiPairingDialogResult(
            confirmed = true,
            pairingAddressText = "192.168.1.42:37000",
            pairingCodeText = "123456",
            connectAddressText = "192.168.1.42:5555",
        )

        assertFalse(result.toString().contains("123456"))
    }
}
