package dev.acme.adbtoolbox.e2e.tests

import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Network view: global HTTP proxy enable/disable, validation, recents and host-IP fill. */
class NetworkE2ETest : E2eTest() {

    @BeforeEach
    fun openNetworkView() {
        clearProxy()
        studio.navigate(View.Network)
        studio.leaveAndReturnToToolWindow()
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "the proxy form") { studio.isShowing(studio.xpathByName("Enable proxy")) }
    }

    @AfterEach
    fun clearProxy() {
        Adb.putSetting("global", "http_proxy", ":0")
        Adb.deleteSetting("global", "http_proxy")
    }

    @Test
    fun `Enable proxy sets the device's global http_proxy and Disable clears it`() {
        enterProxy("10.0.2.2", "8888")

        studio.click("Enable proxy")

        awaitProxy("10.0.2.2:8888")
        studio.waitForText("10.0.2.2:8888")
        studio.click("Disable")
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "proxy cleared") {
            Adb.setting("global", "http_proxy").let { it == "null" || it == ":0" || it.isEmpty() }
        }
    }

    @Test
    fun `an invalid port is rejected without touching the device`() {
        enterProxy("10.0.2.2", "99999")

        // design/README.md §6: "Invalid port → … 'Port must be 1–65535' and the primary action is disabled".
        studio.waitForText("Port must be 1")
        awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "Enable proxy to be disabled") {
            !studio.isEnabled(studio.byName("Enable proxy"))
        }
        Adb.setting("global", "http_proxy").let { it == "null" || it == ":0" || it.isEmpty() } shouldBe true
    }

    @Test
    fun `a used proxy appears under Recent and one click fills the form to re-enable it`() {
        enterProxy("10.0.2.2", "8889")
        studio.click("Enable proxy")
        awaitProxy("10.0.2.2:8889")
        studio.click("Disable")
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "proxy cleared") { Adb.setting("global", "http_proxy") != "10.0.2.2:8889" }

        val recents = studio.toolWindow().find(
            com.intellij.remoterobot.fixtures.JListFixture::class.java,
            com.intellij.remoterobot.search.locators.byXpath("//div[@class='NetworkPanel']//div[@class='JBList']"),
            Duration.ofSeconds(10),
        )
        val index = studio.listModelItems(recents).indexOfFirst { "10.0.2.2:8889" in it || "port=8889" in it }
        check(index >= 0) { "10.0.2.2:8889 not in Recent: ${studio.listModelItems(recents)}" }
        studio.typeInto(studio.byName("Proxy host"), "")
        studio.typeInto(studio.byName("Proxy port"), "")

        recents.clickItemAtIndex(index)

        // design/README.md §6: "Clicking a row fills host+port without enabling."
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "the form to be filled from Recent") {
            studio.textOf(studio.byName("Proxy host")) == "10.0.2.2" && studio.textOf(studio.byName("Proxy port")) == "8889"
        }
        Adb.setting("global", "http_proxy") shouldNotBe "10.0.2.2:8889"
        studio.click("Enable proxy")
        awaitProxy("10.0.2.2:8889")
    }

    @Test
    fun `Use my computer IP fills the host field with a LAN address`() {
        studio.click("Use my computer IP")

        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "the host field to be filled") {
            studio.textOf(studio.byName("Proxy host")).isNotBlank()
        }
        studio.textOf(studio.byName("Proxy host")) shouldMatch Regex("""\d+\.\d+\.\d+\.\d+""")
    }

    private fun enterProxy(host: String, port: String) {
        studio.typeInto(studio.byName("Proxy host"), host)
        studio.typeInto(studio.byName("Proxy port"), port)
    }

    private fun awaitProxy(expected: String) =
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "http_proxy=$expected") { Adb.setting("global", "http_proxy") == expected }
}
