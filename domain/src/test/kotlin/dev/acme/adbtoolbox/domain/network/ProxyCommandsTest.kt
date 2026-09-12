package dev.acme.adbtoolbox.domain.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ProxyCommands] builds the exact shell commands `design/IMPLEMENTATION.md` §4 specifies, through
 * [dev.acme.adbtoolbox.domain.adb.AdbShellCommand]'s typed tokens only — never raw string
 * concatenation — so runtime host/port data is always quoted.
 */
class ProxyCommandsTest {

    private val endpoint = ProxyEndpoint(
        host = (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
        port = (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
    )

    @Test
    fun `enable renders the settings put command with the quoted host colon port value`() {
        ProxyCommands.enable(endpoint).render() shouldBe "settings put global http_proxy '10.0.4.117:8888'"
    }

    @Test
    fun `reset renders the approved colon-zero disable form`() {
        ProxyCommands.reset().render() shouldBe "settings put global http_proxy ':0'"
    }

    @Test
    fun `read renders the settings get command`() {
        ProxyCommands.read().render() shouldBe "settings get global http_proxy"
    }

    @Test
    fun `enable quotes a hostname value that could otherwise be shell-special`() {
        val hostileHost = (ProxyHost.parse("proxy.acme.dev") as ProxyHostResult.Valid).host
        val port = (ProxyPort.parse(3128) as ProxyPortResult.Valid).port

        ProxyCommands.enable(ProxyEndpoint(hostileHost, port)).render() shouldBe
            "settings put global http_proxy 'proxy.acme.dev:3128'"
    }
}
