@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.display.toggles

import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.display.toggles.ScreenRotation
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private class Dispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

/** A device as `settings`, `getprop`, `svc` and `cmd connectivity` see it. */
private class FakeDevice(
    val sdk: Int = 34,
    val settings: MutableMap<String, String> = mutableMapOf(),
    val properties: MutableMap<String, String> = mutableMapOf(),
    var airplane: Boolean = false,
)

private class FakeTransport(private val devices: Map<DeviceSerial, FakeDevice>) : AdbTransport {
    val commands = mutableListOf<String>()
    val delays = mutableMapOf<String, Long>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val deviceRequest = request as AdbDeviceRequest
        val rendered = (deviceRequest.operation as AdbOperation.Shell).command.render()
        commands += rendered
        delays[rendered]?.let { delay(it) }
        val device = devices.getValue(deviceRequest.serial)
        val out = rendered.split(" ; ").joinToString("\n") { run(device, it.split(" ")) }
        return AdbTextResult(AdbOutcome.Completed(0), out, "")
    }

    private fun run(device: FakeDevice, argv: List<String>): String = when {
        argv == listOf("getprop", "ro.build.version.sdk") -> device.sdk.toString()
        argv[0] == "getprop" -> device.properties[argv[1]].orEmpty()
        argv[0] == "setprop" -> { device.properties[argv[1]] = argv[2]; "" }
        argv.take(2) == listOf("settings", "get") -> device.settings["${argv[2]}/${argv[3]}"] ?: "null"
        argv.take(2) == listOf("settings", "put") -> { device.settings["${argv[2]}/${argv[3]}"] = argv[4]; "" }
        argv.take(2) == listOf("svc", "wifi") -> { device.settings["global/wifi_on"] = if (argv[2] == "enable") "1" else "0"; "" }
        argv.take(2) == listOf("svc", "data") -> { device.settings["global/mobile_data"] = if (argv[2] == "enable") "1" else "0"; "" }
        argv == listOf("cmd", "connectivity", "airplane-mode") -> if (device.airplane) "enabled" else "disabled"
        argv.take(3) == listOf("cmd", "connectivity", "airplane-mode") -> { device.airplane = argv[3] == "enable"; "" }
        argv.take(3) == listOf("service", "call", "activity") -> "Result: Parcel(Error: 0xffffffb6 \"Not a data message\")"
        else -> error("unscripted command: $argv")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = throw UnsupportedOperationException()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = throw UnsupportedOperationException()
}

class DeviceSettingTogglesViewModelTest {

    private class Harness(
        val devices: Map<DeviceSerial, FakeDevice> = mapOf(serialA to FakeDevice()),
        initial: SelectedDeviceState = online(serialA),
    ) {
        val scope = TestScope()
        val transport = FakeTransport(devices)
        val selected = MutableStateFlow(initial)
        val viewModel = DeviceSettingTogglesViewModel(
            scope = scope,
            dispatchers = Dispatchers(StandardTestDispatcher(scope.testScheduler)),
            transport = transport,
            selectedDeviceState = selected,
        )

        fun toggle(toggle: DeviceSettingToggle) = viewModel.state.value.toggles.getValue(toggle)
    }

    @Test
    fun `selecting a device reads every toggle and the rotation`() {
        val device = FakeDevice(
            settings = mutableMapOf("global/wifi_on" to "1", "system/accelerometer_rotation" to "0", "system/user_rotation" to "1"),
            properties = mutableMapOf("debug.layout" to "true"),
            airplane = true,
        )
        val h = Harness(mapOf(serialA to device))

        h.scope.advanceUntilIdle()

        h.toggle(DeviceSettingToggle.ShowLayoutBounds) shouldBe QuickToggleFieldState.Idle(true)
        h.toggle(DeviceSettingToggle.GpuOverdraw) shouldBe QuickToggleFieldState.Idle(false)
        h.toggle(DeviceSettingToggle.AirplaneMode) shouldBe QuickToggleFieldState.Idle(true)
        h.toggle(DeviceSettingToggle.Wifi) shouldBe QuickToggleFieldState.Idle(true)
        h.toggle(DeviceSettingToggle.MobileData) shouldBe QuickToggleFieldState.Idle(false)
        h.viewModel.state.value.rotation shouldBe QuickToggleFieldState.Idle(ScreenRotation.Landscape)
    }

    @Test
    fun `no eligible device touches no device`() {
        val h = Harness(initial = SelectedDeviceState.None)

        h.scope.advanceUntilIdle()

        h.viewModel.state.value shouldBe DeviceSettingTogglesViewState()
        h.transport.commands shouldBe emptyList()
    }

    @Test
    fun `a switch is written and confirmed by its readback`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeviceSettingTogglesIntent.SetToggle(DeviceSettingToggle.GpuOverdraw, true))
        h.scope.advanceUntilIdle()

        h.transport.commands.takeLast(4) shouldBe listOf(
            "getprop debug.hwui.overdraw",
            "setprop debug.hwui.overdraw show",
            "service call activity 1599295570",
            "getprop debug.hwui.overdraw",
        )
        h.toggle(DeviceSettingToggle.GpuOverdraw) shouldBe QuickToggleFieldState.Idle(true)
    }

    @Test
    fun `a switch already in the requested state is not written`() {
        val h = Harness(mapOf(serialA to FakeDevice(settings = mutableMapOf("global/wifi_on" to "1"))))
        h.scope.advanceUntilIdle()
        val before = h.transport.commands.size

        h.viewModel.handle(DeviceSettingTogglesIntent.SetToggle(DeviceSettingToggle.Wifi, true))
        h.scope.advanceUntilIdle()

        h.transport.commands.drop(before) shouldBe listOf("settings get global wifi_on")
    }

    @Test
    fun `bold text below Android 12 is not supported`() {
        val h = Harness(mapOf(serialA to FakeDevice(sdk = 30)))

        h.scope.advanceUntilIdle()

        h.toggle(DeviceSettingToggle.BoldText) shouldBe QuickToggleFieldState.Error("Not supported on this device", null)
    }

    @Test
    fun `rotation locks and unlocks with a readback`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeviceSettingTogglesIntent.SetRotation(ScreenRotation.Landscape))
        h.scope.advanceUntilIdle()
        h.viewModel.state.value.rotation shouldBe QuickToggleFieldState.Idle(ScreenRotation.Landscape)

        h.viewModel.handle(DeviceSettingTogglesIntent.SetRotation(ScreenRotation.Auto))
        h.scope.advanceUntilIdle()
        h.viewModel.state.value.rotation shouldBe QuickToggleFieldState.Idle(ScreenRotation.Auto)
    }

    @Test
    fun `a device switch mid-write drops the stale readback`() {
        val h = Harness(mapOf(serialA to FakeDevice(), serialB to FakeDevice()))
        h.scope.advanceUntilIdle()
        h.transport.delays["settings put system pointer_location 1"] = 1_000

        h.viewModel.handle(DeviceSettingTogglesIntent.SetToggle(DeviceSettingToggle.PointerLocation, true))
        h.scope.testScheduler.advanceTimeBy(500)
        h.selected.value = online(serialB)
        h.scope.advanceUntilIdle()

        h.toggle(DeviceSettingToggle.PointerLocation) shouldBe QuickToggleFieldState.Idle(false)
    }

    private companion object {
        fun online(serial: DeviceSerial) =
            SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))
    }
}
