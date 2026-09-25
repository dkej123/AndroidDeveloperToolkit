package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Logcat view: live stream, follow, search, level filter, pause/resume, clear, package filter. */
class LogcatE2ETest : E2eTest() {

    @BeforeEach
    fun openLogcatView() {
        studio.navigate(View.Logcat)
        // "Limit to selected app" is on by default; after the Apps tests an app is selected and
        // the stream would only show that app's lines. These tests need the whole stream.
        val packageChip = studio.byName("Limit to selected app")
        if (studio.textOf(packageChip) != "all packages") studio.click("Limit to selected app")
        awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "the package filter to be off") {
            studio.textOf(studio.byName("Limit to selected app")) == "all packages"
        }
    }

    @AfterEach
    fun resetControls() {
        studio.setTextOf(studio.byName("Search log"), "")
        studio.click("Minimum Logcat level: V")
        if (studio.isSelected(studio.byName("Pause the stream"))) studio.click("Pause the stream")
    }

    @Test
    fun `opening Logcat follows the newest line`() {
        // design/README.md §7: autoscroll is on by default, so the view starts at the bottom.
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "log lines") { size() > 0 }
        studio.navigate(View.Device)
        studio.navigate(View.Logcat)
        Thread.sleep(1000)

        val (lastVisible, size) = visibleRange()
        lastVisible shouldBeGreaterThanOrEqual size - 3
    }

    @Test
    fun `a line logged on the device appears in the stream`() {
        val marker = marker()

        Adb.log("E2E", marker)

        awaitRow(marker)
    }

    @Test
    fun `search keeps only matching lines`() {
        val marker = marker()
        Adb.log("E2E", marker)
        awaitRow(marker)

        studio.typeInto(studio.byName("Search log"), marker)

        awaitUntil(Duration.ofSeconds(15), Duration.ofMillis(300), "only the matching line") {
            count(marker).let { (matching, size) -> size > 0 && matching == size }
        }
    }

    @Test
    fun `minimum level E hides info lines and keeps errors`() {
        studio.click("Minimum Logcat level: E")
        val info = marker()
        val error = marker()

        Adb.log("E2E", info, priority = "i")
        Adb.log("E2E", error, priority = "e")

        awaitRow(error)
        matches(info) shouldBe 0
    }

    @Test
    fun `pause freezes the view and resume shows what arrived meanwhile`() {
        studio.click("Pause the stream")
        val whilePaused = marker()

        Adb.log("E2E", whilePaused)
        Thread.sleep(E2eConfig.deviceTimeout(3).toMillis())
        matches(whilePaused) shouldBe 0

        studio.click("Pause the stream")
        awaitRow(whilePaused)
    }

    @Test
    fun `clear empties the local buffer but keeps streaming`() {
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "log lines") { size() > 0 }

        studio.click("Clear the buffer — does not clear the device log")

        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(300), "an empty or nearly empty list") { size() < 20 }
        val after = marker()
        Adb.log("E2E", after)
        awaitRow(after)
    }

    @Test
    fun `footer counts visible and retained lines`() {
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "log lines") { size() > 0 }
        studio.visibleTexts().joinToString("\n") shouldContain " lines"
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun list(): JListFixture =
        studio.toolWindow().find(JListFixture::class.java, byXpath("//div[@class='LogcatVirtualList']"), Duration.ofSeconds(10))

    /** Rows are counted inside the IDE: the model can hold 100k lines, too many to ship over HTTP. */
    private fun count(text: String = ""): Pair<Int, Int> {
        val raw: String = list().callJs(
            "var m = component.getModel(); var n = 0; for (var i = 0; i < m.getSize(); i++) { if ((m.getElementAt(i) + '').indexOf(${dev.acme.adbtoolbox.e2e.infra.Studio.quoteJs(text)}) >= 0) n++; } n + ',' + m.getSize()",
            true,
        )
        return raw.split(',').let { it[0].toInt() to it[1].toInt() }
    }

    private fun size(): Int = count().second
    private fun matches(text: String): Int = count(text).first

    private fun visibleRange(): Pair<Int, Int> {
        val raw: String = list().callJs("component.getLastVisibleIndex() + ',' + component.getModel().getSize()", true)
        return raw.split(',').let { it[0].toInt() to it[1].toInt() }
    }

    private fun awaitRow(text: String) =
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(300), "a Logcat row containing $text") { matches(text) > 0 }

    private fun marker() = "e2e-" + UUID.randomUUID().toString().take(8)
}
