package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Display controls — font scale, density (presets, custom, reset) and quick toggles — which live in
 * the Device view (there is no Display view), checked on-device. */
class DisplayE2ETest : E2eTest() {

    @BeforeEach
    fun openDisplayView() {
        resetDevice()
        studio.navigate(View.Device)
        // The reset above happened outside the plugin, like a change from a terminal.
        studio.leaveAndReturnToToolWindow()
    }

    @AfterEach
    fun resetDevice() {
        Adb.putSetting("system", "font_scale", "1.0")
        Adb.shell("wm density reset")
        Adb.shell("cmd uimode night no")
        Adb.putSetting("system", "show_touches", "0")
        Adb.shell("settings delete secure enabled_accessibility_services")
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .forEach { Adb.putSetting("global", it, "1.0") }
        Adb.shell("setprop debug.hwui.show_dirty_regions false")
        // Only the activity manager itself can clear these (ADR 0012): use the helper the plugin pushed.
        Adb.shell(
            "for j in /data/local/tmp/adb-toolbox-app-info-*.jar; do [ -f \"\$j\" ] && " +
                "CLASSPATH=\$j app_process / dev.acme.adbtoolbox.devicehelper.DevOptionsMain always-finish 0 && " +
                "CLASSPATH=\$j app_process / dev.acme.adbtoolbox.devicehelper.DevOptionsMain process-limit -1; done",
        )
    }

    @Test
    fun `font scale preset is applied on the device`() {
        studio.choose("Font scale", "1.3×")

        awaitDevice("font_scale 1.3") { Adb.setting("system", "font_scale").toFloat() == 1.3f }
    }

    @Test
    fun `font scale reset restores the device default`() {
        studio.choose("Font scale", "1.5×")
        awaitDevice("font_scale 1.5") { Adb.setting("system", "font_scale").toFloat() == 1.5f }

        studio.choose("Font scale", "1×")

        awaitDevice("default font scale") { Adb.setting("system", "font_scale").let { it == "null" || it.toFloat() == 1.0f } }
    }

    @Test
    fun `custom font scale is applied and invalid input is rejected`() {
        studio.choose("Font scale", "Custom…")
        val field = visibleCustomField()

        studio.typeInto(field, "9")
        studio.clickWhenShowing { visibleApply() }
        studio.waitForText("must be between")
        Adb.setting("system", "font_scale").toFloat() shouldBe 1.0f

        studio.typeInto(field, "1.7")
        studio.clickWhenShowing { visibleApply() }
        awaitDevice("font_scale 1.7") { Adb.setting("system", "font_scale").toFloat() == 1.7f }
    }

    @Test
    fun `density preset overrides and Reset to physical restores it`() {
        val physical = Adb.shell("wm density").lines().first().substringAfterLast(": ").trim().toInt()

        studio.choose("Display scale", "110%")

        awaitDevice("override density ${physical * 110 / 100}") { overrideDensity() == physical * 110 / 100 }
        studio.click("Reset to physical")
        awaitDevice("density override removed") { overrideDensity() == null }
    }

    @Test
    fun `custom density in dpi is applied`() {
        studio.choose("Display scale", "Custom…")
        studio.typeInto(visibleCustomField(), "400")
        studio.clickWhenShowing { visibleApply() }

        awaitDevice("override density 400") { overrideDensity() == 400 }
    }

    @Test
    fun `dark theme toggle switches ui night mode`() {
        studio.click("Dark theme", "ToggleSwitch")

        awaitDevice("night mode yes") { "yes" in Adb.shell("cmd uimode night") }
        studio.click("Dark theme", "ToggleSwitch")
        awaitDevice("night mode no") { "no" in Adb.shell("cmd uimode night") }
    }

    @Test
    fun `animations off sets all three animation scales to zero`() {
        studio.click("Animations off", "ToggleSwitch")

        awaitDevice("animation scales 0") {
            listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
                .all { Adb.setting("global", it).toFloatOrNull() == 0f }
        }
    }

    @Test
    fun `show touches toggle writes the system setting`() {
        studio.click("Show touches", "ToggleSwitch")

        awaitDevice("show_touches 1") { Adb.setting("system", "show_touches") == "1" }
    }

    @Test
    fun `TalkBack without a known TalkBack installed asks for a custom command and changes nothing`() {
        // The API 28 emulator image ships no TalkBack at all (like a device without GMS).
        check(!Adb.shell("pm list packages talkback").contains("talkback")) { "fixture device unexpectedly has TalkBack" }
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "TalkBack value to load") {
            studio.valueAfterCaption("TalkBack") == "off"
        }

        studio.click("TalkBack", "ToggleSwitch")

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "the missing-TalkBack message") {
            studio.visibleTexts().any { "No known TalkBack installed" in it }
        }
        Adb.setting("secure", "enabled_accessibility_services").contains("talkback", ignoreCase = true) shouldBe false
    }

    @Test
    fun `stay awake toggle flips the plugged-in stay-on setting`() {
        val initiallyOn = Adb.setting("global", "stay_on_while_plugged_in").toIntOrNull()?.let { it != 0 } ?: false
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "Stay awake value to load") {
            studio.valueAfterCaption("Stay awake") == if (initiallyOn) "on" else "off"
        }

        studio.click("Stay awake", "ToggleSwitch")
        awaitDevice("stay awake flipped") { (Adb.setting("global", "stay_on_while_plugged_in") != "0") != initiallyOn }

        studio.click("Stay awake", "ToggleSwitch")
        awaitDevice("stay awake restored") { (Adb.setting("global", "stay_on_while_plugged_in") != "0") == initiallyOn }
    }

    @Test
    fun `don't keep activities is applied through the activity manager`() {
        studio.click("Don't keep activities", "ToggleSwitch")

        awaitDevice("always_finish_activities 1") { Adb.setting("global", "always_finish_activities") == "1" }
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "Don't keep activities to read on") {
            studio.valueAfterCaption("Don't keep activities") == "on"
        }
        studio.click("Don't keep activities", "ToggleSwitch")
        awaitDevice("always_finish_activities 0") { Adb.setting("global", "always_finish_activities") == "0" }
    }

    @Test
    fun `show view updates sets the hwui debug property`() {
        studio.click("Show view updates", "ToggleSwitch")

        awaitDevice("show_dirty_regions true") { Adb.shell("getprop debug.hwui.show_dirty_regions").trim() == "true" }
    }

    @Test
    fun `show surface updates works with adb root and explains itself without it`() {
        val root = Adb.shell("id").contains("uid=0(")
        if (root) {
            studio.click("Show surface updates", "ToggleSwitch")
            awaitDevice("SurfaceFlinger show updates on") {
                Adb.shell("service call SurfaceFlinger 1010").contains(Regex("0x00000000: [0-9a-f]{8} [0-9a-f]{8} 0*[1-9a-f]"))
            }
            studio.click("Show surface updates", "ToggleSwitch")
            awaitDevice("SurfaceFlinger show updates off") {
                Adb.shell("service call SurfaceFlinger 1010").contains("0x00000000: 00000000 00000000 00000000")
            }
        } else {
            awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "surface updates to report n/a") {
                studio.valueAfterCaption("Show surface updates") == "n/a"
            }
        }
    }

    @Test
    fun `background process limit chips set and clear the activity manager limit`() {
        studio.click("2", "PresetChip")
        awaitDevice("process limit 2") { "CUR_MAX_CACHED_PROCESSES=2" in Adb.shell("dumpsys activity settings") }
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "the limit to read back") {
            studio.valueAfterCaption("Background process limit") == "2 max"
        }

        studio.click("Standard", "PresetChip")
        awaitDevice("standard limit") { "CUR_MAX_CACHED_PROCESSES=2\n" !in Adb.shell("dumpsys activity settings") + "\n" }
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "the standard limit to read back") {
            studio.valueAfterCaption("Background process limit") == "standard"
        }
    }

    @Test
    fun `quick toggle values reflect the device state`() {
        // The value column next to each toggle shows the device's current value, never a placeholder.
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "toggle values to load") {
            listOf("Animations off", "Show touches", "TalkBack", "Stay awake", "Don't keep activities", "Show view updates", "Background process limit")
                .none { studio.valueAfterCaption(it) == "—" }
        }
    }

    private fun visibleCustomField(): ComponentFixture {
        var field: ComponentFixture? = null
        awaitUntil(Duration.ofSeconds(5), Duration.ofMillis(200), "the custom value field") {
            field = studio.toolWindow().findAll(ComponentFixture::class.java, byXpath("//div[@class='DisplayPanel']//div[@class='JBTextField']"))
                .firstOrNull { it.isShowing }
            field != null
        }
        return field!!
    }

    private fun visibleApply(): ComponentFixture =
        studio.toolWindow().findAll(ComponentFixture::class.java, byXpath("//div[@accessiblename='Apply']")).first { it.isShowing }

    private fun overrideDensity(): Int? =
        Adb.shell("wm density").lines().firstOrNull { it.startsWith("Override density") }?.substringAfterLast(": ")?.trim()?.toInt()

    private fun awaitDevice(what: String, condition: () -> Boolean) =
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), what, condition)
}
