package io.github.dkej123.devicecockpit.e2e.tests

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import io.github.dkej123.devicecockpit.e2e.infra.Adb
import io.github.dkej123.devicecockpit.e2e.infra.E2eConfig
import io.github.dkej123.devicecockpit.e2e.infra.E2eTest
import io.github.dkej123.devicecockpit.e2e.infra.View
import io.github.dkej123.devicecockpit.e2e.infra.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun `Accessibility audit opens an Inspector tab in its Accessibility mode`() {
        studio.click("Accessibility audit")

        // The chip is in the editor tab, outside the tool window.
        val chip = studio.robot.find(
            com.intellij.remoterobot.fixtures.ComponentFixture::class.java,
            com.intellij.remoterobot.search.locators.byXpath("//div[@class='JToggleButton' and starts-with(@accessiblename, 'Accessibility')]"),
            E2eConfig.deviceTimeout(30),
        )
        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "the Inspector tab in Accessibility mode") { studio.isSelected(chip) }
        studio.invokeAction("CloseAllEditors")
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
    fun `Full page saves the foreground app's whole content and gives the app back`() {
        assumeTrue(Adb.shell("getprop ro.build.version.sdk").trim().toInt() >= 29, "full-page screenshots need API 29+")
        Adb.shell("am start -W -a android.settings.SETTINGS")
        val screenHeight = Adb.shell("wm size").substringAfterLast(": ").trim().split("x")[1].toInt()

        // Full page lives in the Screenshot split button's menu (design handoff 4, user decision 2026-10-08).
        studio.click("More screenshot options")
        studio.clickMenuItem("Full page")

        val png = awaitNewFile("-full.png", E2eConfig.deviceTimeout(60))
        ImageIO.read(png).height shouldBeGreaterThan screenHeight
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "Settings back on the main display") {
            Adb.shell("dumpsys activity activities").substringAfter("Display #0").contains("com.android.settings")
        }
        Adb.shell("ls /data/local/tmp").contains("adbtoolbox-fullshot.png") shouldBe false
    }

    @Test
    fun `Record then Stop and save writes a finished MP4 through scrcpy`() {
        studio.click("Record")
        studio.byName("Stop & save", timeout = E2eConfig.deviceTimeout(10))
        // scrcpy 2.4+ records on the host, so there is no 3-minute screenrecord limit.
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "a scrcpy --record process") {
            scrcpyProcesses().any { p -> p.info().arguments().orElse(emptyArray()).any { it.startsWith("--record=") } }
        }
        // scrcpy needs a few seconds to connect before the first frame lands in its temp file.
        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofMillis(500), "scrcpy writing the recording") {
            captureDir.listFiles().orEmpty().any { it.name.startsWith(".capture-") && it.length() > 0 }
        }
        Thread.sleep(E2eConfig.deviceTimeout(3).toMillis())

        studio.click("Stop & save")

        val mp4 = awaitNewFile(".mp4", E2eConfig.deviceTimeout(30))
        mp4.length() shouldBeGreaterThan 1_000L
        // Stopped with SIGTERM, scrcpy still writes the index an MP4 player needs.
        String(mp4.readBytes(), Charsets.ISO_8859_1).contains("moov") shouldBe true
        studio.byName("Record", timeout = E2eConfig.deviceTimeout(10))
        scrcpyProcesses() shouldBe emptyList()
    }

    @Test
    fun `mirroring header shows the installed scrcpy version`() {
        val installed = ProcessBuilder(scrcpyExecutable(), "--version").start().inputStream.bufferedReader().readLine()
            .substringAfter("scrcpy ").substringBefore(" ")
        // Screen header meta: "<capture dir> · scrcpy <version>".
        studio.visibleTexts().any { it.endsWith("scrcpy $installed") } shouldBe true
    }

    @Test
    fun `Start mirroring launches scrcpy for the device and Stop ends it`() {
        startMirroring()

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
        // The Mirror button opens the options in a dialog; they are saved as they change.
        startMirroring { dialog ->
            option(dialog, "Bitrate", "ComboBox").runJs("component.setSelectedItem(java.lang.Integer.valueOf(4))", true)
        }

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy started with 4M video bit rate") {
            scrcpyProcesses().any { process ->
                val args = process.info().arguments().orElse(emptyArray()).joinToString(" ")
                "4M" in args || "4000000" in args
            }
        }
        studio.click("Stop")
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy to exit") { scrcpyProcesses().isEmpty() }
        // Leave the persisted options as the other tests expect them; Cancel keeps the change.
        editOptionsWithoutStarting { dialog -> option(dialog, "Bitrate", "ComboBox").runJs("component.setSelectedItem(null)", true) }
    }

    @Test
    fun `Cancel in the mirroring dialog does not start scrcpy`() {
        studio.click("Start mirroring")
        studio.dialogButton(studio.dialog(START_DIALOG), "Cancel").click()

        Thread.sleep(2_000)
        scrcpyProcesses() shouldBe emptyList()
        studio.byName("Start mirroring")
    }

    @Test
    fun `Show touches from the options panel reaches scrcpy and the device`() {
        // Regression: saved options the Start button never saw until an IDE restart.
        Adb.putSetting("system", "show_touches", "0")
        // The option is persisted, so a failed earlier run can leave it on: set it, don't toggle it.
        startMirroring { dialog -> setShowTouches(dialog, true) }

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy started with --show-touches") {
            scrcpyProcesses().any { "--show-touches" in it.info().arguments().orElse(emptyArray()) }
        }
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "show_touches on while mirroring") {
            Adb.setting("system", "show_touches") == "1"
        }
        studio.click("Stop")
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy to exit") { scrcpyProcesses().isEmpty() }

        // Leave the persisted options as the other tests expect them.
        editOptionsWithoutStarting { dialog -> setShowTouches(dialog, false) }
    }

    /** Clicks Mirror, sets [options] in its dialog, then starts from the dialog. */
    private fun startMirroring(options: (ContainerFixture) -> Unit = {}) {
        studio.click("Start mirroring")
        val dialog = studio.dialog(START_DIALOG)
        options(dialog)
        studio.dialogButton(dialog, "Start mirroring").click()
    }

    private fun editOptionsWithoutStarting(options: (ContainerFixture) -> Unit) {
        studio.click("Start mirroring")
        val dialog = studio.dialog(START_DIALOG)
        options(dialog)
        studio.dialogButton(dialog, "Cancel").click()
    }

    private fun setShowTouches(dialog: ContainerFixture, on: Boolean) {
        val box = option(dialog, "Show touches", "JBCheckBox")
        if (studio.isSelected(box) != on) box.click()
    }

    /** The options live in the start dialog, outside the tool window [Studio.byName] searches. */
    private fun option(dialog: ContainerFixture, name: String, cls: String): ComponentFixture =
        dialog.find(ComponentFixture::class.java, byXpath(studio.xpathByName(name, cls)), E2eConfig.deviceTimeout(15))

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

    private companion object {
        const val START_DIALOG = "Start Mirroring"
    }
}
