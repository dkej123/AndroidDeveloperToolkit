package dev.acme.adbtoolbox.e2e.tests

import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Device view: Screenshot, Record, and scrcpy mirroring (start, stop, options). */
class CaptureAndMirroringE2ETest : E2eTest() {

    private val captureDir = File(System.getProperty("user.home"), "Desktop")
    private lateinit var before: Set<String>

    @BeforeEach
    fun openDeviceView() {
        studio.navigate(View.Device)
        before = captureDir.list().orEmpty().toSet()
    }

    @AfterEach
    fun cleanUp() {
        captureDir.listFiles().orEmpty().filter { it.name !in before }.forEach { it.delete() }
        scrcpyProcesses().forEach { it.destroy() }
    }

    @Test
    fun `Screenshot saves a PNG of the device screen`() {
        studio.click("Screenshot")

        val png = awaitNewFile(".png", E2eConfig.deviceTimeout(15))
        val image = ImageIO.read(png)
        val (width, height) = Adb.shell("wm size").substringAfterLast(": ").trim().split("x").map(String::toInt)
        (image.width to image.height) shouldBe (width to height)
    }

    @Test
    fun `Record then Stop and save writes an MP4`() {
        studio.click("Record")
        studio.byName("Stop & save", timeout = E2eConfig.deviceTimeout(10))
        Thread.sleep(E2eConfig.deviceTimeout(3).toMillis())

        studio.click("Stop & save")

        val mp4 = awaitNewFile(".mp4", E2eConfig.deviceTimeout(30))
        mp4.length() shouldBeGreaterThan 1_000L
        studio.byName("Record", timeout = E2eConfig.deviceTimeout(10))
    }

    @Test
    fun `mirroring header shows the installed scrcpy version`() {
        val installed = ProcessBuilder(scrcpyExecutable(), "--version").start().inputStream.bufferedReader().readLine()
            .substringAfter("scrcpy ").substringBefore(" ")
        studio.visibleTexts() shouldContain "scrcpy $installed"
    }

    @Test
    fun `Start mirroring launches scrcpy for the device and Stop ends it`() {
        studio.click("Start mirroring")

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "a scrcpy process for ${E2eConfig.serial}") {
            scrcpyProcesses().any { E2eConfig.serial in it.info().arguments().orElse(emptyArray()).joinToString(" ") }
        }
        studio.byName("Stop", timeout = E2eConfig.deviceTimeout(15))

        studio.click("Stop")

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy to exit") { scrcpyProcesses().isEmpty() }
        studio.byName("Start mirroring", timeout = E2eConfig.deviceTimeout(10))
    }

    @Test
    fun `mirroring options are persisted and passed to scrcpy`() {
        studio.click("Mirroring options")
        val dialog = studio.dialog("Mirroring Options")
        val bitRate = dialog.find(com.intellij.remoterobot.fixtures.JTextFieldFixture::class.java,
            com.intellij.remoterobot.search.locators.byXpath("//div[@name='mirroringVideoBitRateField']"), Duration.ofSeconds(5))
        bitRate.text = "4"
        studio.dialogButton(dialog, "OK").click()

        studio.click("Start mirroring")

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy started with 4M video bit rate") {
            scrcpyProcesses().any { process ->
                val args = process.info().arguments().orElse(emptyArray()).joinToString(" ")
                "4M" in args || "4000000" in args
            }
        }
        studio.click("Stop")
    }

    private fun awaitNewFile(suffix: String, timeout: Duration): File {
        var found: File? = null
        awaitUntil(timeout, Duration.ofMillis(500), "a new *$suffix in $captureDir") {
            found = captureDir.listFiles().orEmpty().firstOrNull { it.name !in before && it.name.endsWith(suffix) && it.length() > 0 }
            found != null
        }
        Thread.sleep(500) // let the writer finish
        return found!!
    }

    private fun scrcpyExecutable(): String =
        System.getenv("E2E_SCRCPY_HOME")?.let { "$it/scrcpy" } ?: "scrcpy"

    private fun scrcpyProcesses(): List<ProcessHandle> = ProcessHandle.allProcesses()
        .filter { it.info().command().orElse("").endsWith("/scrcpy") && it.isAlive }
        .toList()
        .also { TimeUnit.MILLISECONDS.sleep(0) }
}
