package io.github.dkej123.devicecockpit.e2e.tests

import io.github.dkej123.devicecockpit.e2e.infra.Adb
import io.github.dkej123.devicecockpit.e2e.infra.E2eConfig
import io.github.dkej123.devicecockpit.e2e.infra.E2eTest
import io.github.dkej123.devicecockpit.e2e.infra.View
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The tool window opens in Android Studio, finds the emulator, and every rail view renders. */
@Tag("smoke")
class ToolWindowSmokeE2ETest : E2eTest() {

    @Test
    fun `device bar shows the connected emulator as the selected device`() {
        val model = Adb.prop("ro.product.model").replace('_', ' ') // adb devices -l: spaces → "_"
        val texts = studio.visibleTexts(studio.component("//div[@class='DeviceContextBarPanel']"))

        texts shouldContain model
        texts shouldContain E2eConfig.serial
        texts shouldContain "1 online"
    }

    @Test
    fun `every rail view can be opened`() {
        for (view in View.entries.filter { it.panelClass != null }) {
            studio.navigate(view)
            studio.isShowing("//div[@class='${view.panelClass}']") shouldBe true
        }
        studio.navigate(View.Device)
    }

    @Test
    fun `plugin runs on Android Studio's ddmlib transport`() {
        val lines = pluginLog.linesSince(io.github.dkej123.devicecockpit.e2e.infra.PluginLog.Mark(0))
        // Diagnostics report whether the Android plugin is loaded (no version: see InstalledPlugins).
        lines.last { "diagnostics started" in it } shouldContain "androidPlugin=loaded"
        lines.last { "project opened" in it } shouldContain "androidPlugin=true"
    }
}
