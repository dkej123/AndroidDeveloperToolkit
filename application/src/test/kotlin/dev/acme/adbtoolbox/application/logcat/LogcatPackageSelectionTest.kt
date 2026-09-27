@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("emulator-5554")

class LogcatPackageSelectionTest {

    @Test
    fun `the logcat package is chosen on its own and follows the selected device`() {
        val scope = TestScope()
        val device = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val selection = LogcatPackageSelection(scope.backgroundScope, device)

        selection.select("com.acme.shop")
        scope.runCurrent()
        selection.state.value shouldBe SelectedPackageState.None

        device.value = SelectedDeviceState.Online(Device(SERIAL, DeviceConnectionState.Online))
        scope.runCurrent()
        selection.state.value shouldBe SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.shop"))
        selection.packageName.value shouldBe "com.acme.shop"

        selection.select(null)
        scope.runCurrent()
        selection.state.value shouldBe SelectedPackageState.None
    }

    @Test
    fun `picker choices list pinned apps first, then the rest, keeping label order`() {
        val entries = listOf(
            PackageEntry("com.acme.alpha", "Alpha", labelResolved = true, isDebuggable = false),
            PackageEntry("com.acme.beta", "Beta", labelResolved = true, isDebuggable = true),
            PackageEntry("com.acme.zebra", "Zebra", labelResolved = true, isDebuggable = false),
        )

        val choices = LogcatPackageChoice.from(entries, pinned = setOf("com.acme.zebra"))

        choices.map { it.packageName to it.pinned } shouldBe listOf(
            "com.acme.zebra" to true,
            "com.acme.alpha" to false,
            "com.acme.beta" to false,
        )
        choices.first().label shouldBe "Zebra"
    }
}
