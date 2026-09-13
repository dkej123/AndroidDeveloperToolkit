package dev.acme.adbtoolbox.domain.wifi

import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

/**
 * [WifiPairingCommands] builds the exact `adb pair <host:port> <code>` / `adb connect <host:port>`
 * argv `design/IMPLEMENTATION.md` §4 specifies, as server-scoped [AdbServerRequest]s (ADR 0005:
 * wireless pair/connect always uses the binary transport) — never as remote-shell text, so no
 * shell-escaping applies and the pairing code is passed as one literal argv element.
 */
class WifiPairingCommandsTest {

    private val pairingEndpoint = (WifiEndpoint.parse("192.168.1.42:37000") as WifiEndpointResult.Valid).endpoint
    private val connectEndpoint = (WifiEndpoint.parse("192.168.1.42:5555") as WifiEndpointResult.Valid).endpoint
    private val code = (PairingCode.parse("123456") as PairingCodeResult.Valid).code

    @Test
    fun `pair builds the exact adb pair argv`() {
        val request = WifiPairingCommands.pair(pairingEndpoint, code, timeout = 15.seconds)

        request shouldBe AdbServerRequest(
            arguments = listOf("pair", "192.168.1.42:37000", "123456"),
            timeout = 15.seconds,
        )
    }

    @Test
    fun `connect builds the exact adb connect argv`() {
        val request = WifiPairingCommands.connect(connectEndpoint, timeout = 10.seconds)

        request shouldBe AdbServerRequest(
            arguments = listOf("connect", "192.168.1.42:5555"),
            timeout = 10.seconds,
        )
    }
}
