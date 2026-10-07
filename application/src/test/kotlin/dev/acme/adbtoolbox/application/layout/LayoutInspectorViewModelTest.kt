@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.layout

import dev.acme.adbtoolbox.application.currentapp.CurrentAppUseCase
import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")
private class D(d: CoroutineDispatcher) : DispatcherProvider {
    override val default = d
    override val io = d
    override val main = d
}

class LayoutInspectorViewModelTest {
    private var dump = """<hierarchy><node class="a.Root" bounds="[0,0][1080,2400]"><node class="android.widget.Button" text="Pay" clickable="true" bounds="[0,0][100,100]"/></node></hierarchy>"""
    private val transport = FakeAdbTransport(
        textScript = { request ->
            val line = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
            AdbTextResult(
                AdbOutcome.Completed(0),
                when {
                    line == "wm density" -> "Physical density: 420"
                    line.startsWith("uiautomator") -> dump
                    line.startsWith("dumpsys activity activities") -> "  topResumedActivity=ActivityRecord{1 u0 com.acme.shop/.checkout.CheckoutActivity t1}\n--adbtoolbox-window--\n"
                    else -> ""
                },
                "",
            )
        },
        binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1)), AdbOutcome.Completed(0)) },
    )
    private val scope = TestScope()
    private val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(Device(serial, DeviceConnectionState.Online, model = "Pixel_9")))
    private val vm = LayoutInspectorViewModel(scope, D(StandardTestDispatcher(scope.testScheduler)), CaptureLayoutUseCase(transport), CurrentAppUseCase(transport), selected, { "Pixel 9" }, now = { 42L })

    @Test
    fun `captures screen, hierarchy, activity and the audit`() {
        vm.recapture()
        scope.advanceUntilIdle()

        val capture = vm.state.value.capture!!
        capture.deviceName shouldBe "Pixel 9"
        capture.activity shouldBe "com.acme.shop/.checkout.CheckoutActivity"
        capture.snapshot.hierarchy.densityDpi shouldBe 420
        capture.audit.stops.single().spoken shouldBe "Pay, Button, double-tap to activate"
        vm.state.value.capturing shouldBe false
    }

    @Test
    fun `a failed capture keeps the previous one and reports why`() {
        vm.recapture()
        scope.advanceUntilIdle()
        dump = "ERROR: could not get idle state."

        vm.recapture()
        scope.advanceUntilIdle()

        vm.state.value.error shouldBe "Could not read the view hierarchy: no <hierarchy> element."
        vm.state.value.capture!!.capturedAtMillis shouldBe 42L
    }

    @Test
    fun `the capture stays readable when its device goes away`() {
        vm.recapture()
        scope.advanceUntilIdle()

        selected.value = SelectedDeviceState.None
        scope.advanceUntilIdle()

        vm.state.value.deviceGone shouldBe true
        vm.state.value.capture shouldBe vm.state.value.capture
        vm.recapture() // no device: nothing happens
        scope.advanceUntilIdle()
        vm.state.value.capturing shouldBe false
    }
}
