package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Device view: device bar, device facts, Copy report and the non-destructive device actions. */
class DeviceE2ETest : E2eTest() {

    @BeforeEach
    fun openDeviceView() = studio.navigate(View.Device)

    @AfterEach
    fun restoreScreen() {
        Adb.shell("svc power stayon true")
        Adb.shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun `device bar shows the human readable model name`() {
        // design/README.md §1 shows the model as a user reads it ("Pixel 8 Pro"), not adb's
        // underscore-joined `model:` token from `adb devices -l`.
        val model = Adb.prop("ro.product.model")
        studio.visibleTexts(studio.component("//div[@class='DeviceContextBarPanel']")) shouldContain model
    }

    @Test
    fun `device facts load and match what the device reports`() {
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "device facts to load") {
            FACTS.none { studio.valueAfterCaption(it).startsWith("Loading") }
        }
        val release = Adb.prop("ro.build.version.release")
        val sdk = Adb.prop("ro.build.version.sdk")
        val size = Adb.shell("wm size").substringAfterLast(": ").trim()
        val density = Adb.shell("wm density").lines().first().substringAfterLast(": ").trim()
        val battery = Adb.shell("dumpsys battery").lines().first { it.trim().startsWith("level:") }.substringAfter(":").trim()

        studio.valueAfterCaption("ANDROID") shouldBe "$release · API $sdk"
        studio.valueAfterCaption("RESOLUTION") shouldBe size.replace("x", "×")
        studio.valueAfterCaption("DENSITY") shouldBe "$density dpi"
        studio.valueAfterCaption("BATTERY") shouldStartWith "$battery%"
        studio.valueAfterCaption("ABI") shouldBe Adb.prop("ro.product.cpu.abi")
        studio.valueAfterCaption("UPTIME") shouldContain "h"
    }

    @Test
    fun `Copy report puts every fact on the clipboard`() {
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "device facts to load") {
            !studio.valueAfterCaption("ABI").startsWith("Loading")
        }
        studio.setClipboard("")

        studio.click("Copy report")

        awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "the report on the clipboard") { studio.clipboardText().isNotBlank() }
        val report = studio.clipboardText()
        report shouldContain E2eConfig.serial
        report shouldContain Adb.prop("ro.product.cpu.abi")
        report shouldContain Adb.prop("ro.build.version.sdk")
    }

    @Test
    fun `refresh re-queries adb devices`() {
        val mark = pluginLog.mark()

        studio.click("Refresh device list")

        pluginLog.awaitLine(mark, E2eConfig.deviceTimeout(10).toMillis()) { "devices -l" in it && ("finished" in it || "done" in it) }
        awaitSelectedDevice()
    }

    @Test
    fun `device picker lists the emulator and closes with Escape`() {
        studio.clickWhenShowing { studio.component("//div[@class='DeviceContextBarPanel']//div[@tooltiptext='Change device']") }
        val popupItem = "//div[@class='DevicePickerListPanel']"
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(200), "the device picker") {
            studio.robot.findAll(ComponentFixture::class.java, byXpath(popupItem)).isNotEmpty()
        }
        val list = studio.robot.find(ComponentFixture::class.java, byXpath("$popupItem//div[@class='JBList']"), Duration.ofSeconds(5))
        studio.listModelItems(list).joinToString(" ") shouldContain E2eConfig.serial

        studio.pressEscape()

        awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "the picker to close") {
            studio.robot.findAll(ComponentFixture::class.java, byXpath(popupItem)).none { it.isShowing }
        }
    }

    @Test
    fun `Wake turns the screen on`() {
        Adb.shell("svc power stayon false")
        Adb.shell("input keyevent KEYCODE_SLEEP")
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "the screen to turn off") { !screenOn() }

        studio.click("Wake")

        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "the screen to turn on") { screenOn() }
    }

    @Test
    fun `Open shell starts an adb shell for the device in the Terminal tool window`() {
        studio.click("Open shell")

        awaitUntil(Duration.ofSeconds(20), Duration.ofMillis(500), "the Terminal tool window") { studio.isToolWindowVisible("Terminal") }
        awaitUntil(Duration.ofSeconds(20), Duration.ofMillis(500), "a terminal tab for ${E2eConfig.serial}") {
            studio.toolWindowContentNames("Terminal").any { E2eConfig.serial in it || "adb" in it.lowercase() }
        }
        studio.openToolWindow()
    }

    private fun screenOn(): Boolean = Adb.shell("dumpsys power").contains("mWakefulness=Awake")

    private companion object {
        val FACTS = listOf("ANDROID", "RESOLUTION", "DENSITY", "BATTERY", "ABI", "UPTIME")
    }
}
