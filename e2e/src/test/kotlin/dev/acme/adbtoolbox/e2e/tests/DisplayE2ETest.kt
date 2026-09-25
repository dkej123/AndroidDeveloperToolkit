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

/** Display view: font scale, density (presets, custom, reset) and quick toggles, checked on-device. */
class DisplayE2ETest : E2eTest() {

    @BeforeEach
    fun openDisplayView() {
        resetDevice()
        studio.navigate(View.Display)
        // The reset above happened outside the plugin, like a change from a terminal.
        studio.leaveAndReturnToToolWindow()
    }

    @AfterEach
    fun resetDevice() {
        Adb.putSetting("system", "font_scale", "1.0")
        Adb.shell("wm density reset")
        Adb.shell("cmd uimode night no")
        Adb.putSetting("system", "show_touches", "0")
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .forEach { Adb.putSetting("global", it, "1.0") }
    }

    @Test
    fun `font scale preset is applied on the device`() {
        studio.click("1.3×", "PresetChip")

        awaitDevice("font_scale 1.3") { Adb.setting("system", "font_scale").toFloat() == 1.3f }
    }

    @Test
    fun `font scale reset restores the device default`() {
        studio.click("1.5×", "PresetChip")
        awaitDevice("font_scale 1.5") { Adb.setting("system", "font_scale").toFloat() == 1.5f }

        studio.click("1×", "PresetChip")

        awaitDevice("default font scale") { Adb.setting("system", "font_scale").let { it == "null" || it.toFloat() == 1.0f } }
    }

    @Test
    fun `custom font scale is applied and invalid input is rejected`() {
        customChips()[0].click()
        val field = visibleCustomField()

        studio.typeInto(field, "9")
        visibleApply().click()
        studio.waitForText("must be between")
        Adb.setting("system", "font_scale").toFloat() shouldBe 1.0f

        studio.typeInto(field, "1.7")
        visibleApply().click()
        awaitDevice("font_scale 1.7") { Adb.setting("system", "font_scale").toFloat() == 1.7f }
    }

    @Test
    fun `density preset overrides and Reset to physical restores it`() {
        val physical = Adb.shell("wm density").lines().first().substringAfterLast(": ").trim().toInt()

        studio.click("110%", "PresetChip")

        awaitDevice("override density ${physical * 110 / 100}") { overrideDensity() == physical * 110 / 100 }
        studio.click("Reset to physical")
        awaitDevice("density override removed") { overrideDensity() == null }
    }

    @Test
    fun `custom density in dpi is applied`() {
        customChips()[1].click()
        studio.typeInto(visibleCustomField(), "400")
        visibleApply().click()

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
    fun `quick toggle values reflect the device state`() {
        // The value column next to each toggle shows the device's current value, never a placeholder.
        awaitUntil(E2eConfig.deviceTimeout(10), Duration.ofMillis(500), "toggle values to load") {
            listOf("Animations off", "Show touches").none { studio.valueAfterCaption(it) == "—" }
        }
    }

    private fun customChips(): List<ComponentFixture> =
        studio.toolWindow().findAll(ComponentFixture::class.java, byXpath("//div[@class='PresetChip' and @accessiblename='Custom…']"))

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
