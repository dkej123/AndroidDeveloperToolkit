package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/** Device connection lifecycle: Wi-Fi pairing entry point and a full reboot round trip. */
class DeviceLifecycleE2ETest : E2eTest() {

    @Test
    fun `Pair device over Wi-Fi opens the pairing dialog from the device picker`() {
        studio.clickWhenShowing { studio.component("//div[@class='DeviceContextBarPanel']//div[@tooltiptext='Change device']") }
        val link = "//div[@class='DevicePickerListPanel']//div[@accessiblename='Pair device over Wi-Fi…' or @text='Pair device over Wi-Fi…']"
        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(200), "the pairing link") {
            studio.robot.findAll(ComponentFixture::class.java, byXpath(link)).any { it.isShowing }
        }
        studio.robot.find(ComponentFixture::class.java, byXpath(link), Duration.ofSeconds(5)).click()

        val dialog = studio.dialog("Pair device over Wi-Fi")
        studio.dialogButton(dialog, "Cancel").click()
        awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "the dialog to close") { !studio.isDialogOpen("Pair device over Wi-Fi") }
    }

    @Test
    @Tag("destructive")
    fun `Reboot takes the device offline and it is reselected when it comes back`() {
        studio.navigate(View.Device)

        studio.click("Reboot")
        if (studio.isDialogOpen()) studio.dialogButton(studio.dialog(), "Reboot").click()

        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofSeconds(1), "the device to go offline") { !Adb.isBooted() }
        awaitUntil(E2eConfig.deviceTimeout(240), Duration.ofSeconds(2), "the device to boot again") { Adb.isBooted() }
        Adb.run("-s", E2eConfig.serial, "root")
        awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofSeconds(2), "adbd after root") { Adb.isBooted() }
        Adb.shell("svc power stayon true")
        awaitSelectedDevice(E2eConfig.deviceTimeout(60))
        awaitUntil(E2eConfig.deviceTimeout(30), Duration.ofSeconds(1), "facts to reload") {
            !studio.valueAfterCaption("UPTIME").startsWith("Loading")
        }
    }
}
