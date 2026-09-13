package dev.acme.adbtoolbox.domain.wifi

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * [WifiPairingInput.Submitted.toString] must never leak the raw pairing code (task 039: "codes are
 * never persisted or logged") even if something incidentally logs the whole intent/input value.
 */
class WifiPairingInputTest {

    @Test
    fun `toString redacts the pairing code but keeps the addresses for diagnostics`() {
        val submitted = WifiPairingInput.Submitted(
            pairingEndpointText = "192.168.1.42:37000",
            pairingCodeText = "123456",
            connectEndpointText = "192.168.1.42:5555",
        )

        val text = submitted.toString()

        text.shouldNotContain("123456")
        text.shouldContain("192.168.1.42:37000")
        text.shouldContain("192.168.1.42:5555")
    }
}
