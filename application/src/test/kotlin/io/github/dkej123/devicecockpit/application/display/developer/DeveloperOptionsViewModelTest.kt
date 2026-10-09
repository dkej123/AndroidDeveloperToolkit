@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.display.developer

import io.github.dkej123.devicecockpit.application.display.QuickToggleFieldState
import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.display.DisplaySettingRead
import io.github.dkej123.devicecockpit.domain.display.developer.ActivityManagerDebugPort
import io.github.dkej123.devicecockpit.domain.display.developer.BackgroundProcessLimit
import io.github.dkej123.devicecockpit.domain.display.developer.DevOptionsAction
import io.github.dkej123.devicecockpit.domain.display.developer.ShowSurfaceUpdatesCommand
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.kotest.matchers.collections.shouldNotContain
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

/** One device's Developer-options state as the fake transport/helper see it. */
private class FakeDevice(
    var stayOnMask: Int = 0,
    var alwaysFinish: Boolean = false,
    var dirtyRegions: String = "",
    var surfaceUpdates: Boolean = false,
    var surfaceFlingerAllowed: Boolean = true,
    var processLimit: Int = BackgroundProcessLimit.STANDARD,
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
        return when (rendered) {
            "settings get global stay_on_while_plugged_in" -> ok(device.stayOnMask.toString())
            "svc power stayon true" -> { device.stayOnMask = 7; ok("") }
            "svc power stayon false" -> { device.stayOnMask = 0; ok("") }
            "settings get global always_finish_activities" -> ok(if (device.alwaysFinish) "1" else "0")
            "getprop debug.hwui.show_dirty_regions" -> ok(device.dirtyRegions)
            "setprop debug.hwui.show_dirty_regions true" -> { device.dirtyRegions = "true"; ok("") }
            "setprop debug.hwui.show_dirty_regions false" -> { device.dirtyRegions = "false"; ok("") }
            "service call activity 1599295570" -> ok("Result: Parcel(Error: 0xffffffb6 \"Not a data message\")")
            "service call SurfaceFlinger 1010" -> surfaceFlinger(device) {
                ok("Result: Parcel(\n  0x00000000: 00000000 00000000 0000000${if (device.surfaceUpdates) 1 else 0} 00000000 '....')")
            }
            "service call SurfaceFlinger 1002 i32 1" -> surfaceFlinger(device) { device.surfaceUpdates = true; ok("Result: Parcel(NULL)") }
            // SurfaceFlinger's 0 toggles instead of clearing.
            "service call SurfaceFlinger 1002 i32 0" -> surfaceFlinger(device) {
                device.surfaceUpdates = !device.surfaceUpdates
                ok("Result: Parcel(NULL)")
            }
            else -> error("unscripted command: $rendered")
        }
    }

    private fun surfaceFlinger(device: FakeDevice, reply: () -> AdbTextResult) =
        if (device.surfaceFlingerAllowed) reply() else ok("Result: Parcel(Error: 0xffffffff \"Operation not permitted\")")

    private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = "")

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = throw UnsupportedOperationException()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = throw UnsupportedOperationException()
}

private class FakeActivityManager(private val devices: Map<DeviceSerial, FakeDevice>) : ActivityManagerDebugPort {
    val actions = mutableListOf<Pair<DeviceSerial, DevOptionsAction>>()
    var failure: DisplaySettingRead<Int>? = null

    override suspend fun run(serial: DeviceSerial, action: DevOptionsAction): DisplaySettingRead<Int> {
        actions += serial to action
        failure?.let { return it }
        val device = devices.getValue(serial)
        when (action) {
            DevOptionsAction.Read -> Unit
            is DevOptionsAction.SetProcessLimit -> device.processLimit = action.limit
            is DevOptionsAction.SetAlwaysFinish -> device.alwaysFinish = action.enabled
        }
        return DisplaySettingRead.Value(device.processLimit)
    }
}

class DeveloperOptionsViewModelTest {

    private class Harness(
        val devices: Map<DeviceSerial, FakeDevice> = mapOf(serialA to FakeDevice()),
        initial: SelectedDeviceState = online(serialA),
    ) {
        val scope = TestScope()
        val transport = FakeTransport(devices)
        val activityManager = FakeActivityManager(devices)
        val selected = MutableStateFlow(initial)
        val viewModel = DeveloperOptionsViewModel(
            scope = scope,
            dispatchers = Dispatchers(StandardTestDispatcher(scope.testScheduler)),
            transport = transport,
            activityManager = activityManager,
            selectedDeviceState = selected,
        )

        fun toggle(toggle: DeveloperToggle) = viewModel.state.value.toggles.getValue(toggle)
    }

    @Test
    fun `selecting a device reads every toggle and the process limit`() {
        val device = FakeDevice(stayOnMask = 7, dirtyRegions = "true", processLimit = 2)
        val h = Harness(mapOf(serialA to device))

        h.scope.advanceUntilIdle()

        h.toggle(DeveloperToggle.StayAwake) shouldBe QuickToggleFieldState.Idle(true)
        h.toggle(DeveloperToggle.DontKeepActivities) shouldBe QuickToggleFieldState.Idle(false)
        h.toggle(DeveloperToggle.ShowViewUpdates) shouldBe QuickToggleFieldState.Idle(true)
        h.toggle(DeveloperToggle.ShowSurfaceUpdates) shouldBe QuickToggleFieldState.Idle(false)
        h.viewModel.state.value.processLimit shouldBe QuickToggleFieldState.Idle(2)
    }

    @Test
    fun `no eligible device leaves everything Loading and touches no device`() {
        val h = Harness(initial = SelectedDeviceState.None)

        h.scope.advanceUntilIdle()

        h.viewModel.state.value shouldBe DeveloperOptionsViewState()
        h.transport.commands shouldBe emptyList()
    }

    @Test
    fun `stay awake writes through svc and reports the readback`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.StayAwake, true))
        h.scope.advanceUntilIdle()

        h.devices.getValue(serialA).stayOnMask shouldBe 7
        h.toggle(DeveloperToggle.StayAwake) shouldBe QuickToggleFieldState.Idle(true)
    }

    @Test
    fun `show view updates sets the property and pokes running apps`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.ShowViewUpdates, true))
        h.scope.advanceUntilIdle()

        h.transport.commands.takeLast(3) shouldBe listOf(
            "setprop debug.hwui.show_dirty_regions true",
            "service call activity 1599295570",
            "getprop debug.hwui.show_dirty_regions",
        )
        h.toggle(DeveloperToggle.ShowViewUpdates) shouldBe QuickToggleFieldState.Idle(true)
    }

    @Test
    fun `don't keep activities goes through the activity manager helper, then reads the setting back`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.DontKeepActivities, true))
        h.scope.advanceUntilIdle()

        h.activityManager.actions.last() shouldBe (serialA to DevOptionsAction.SetAlwaysFinish(true))
        h.toggle(DeveloperToggle.DontKeepActivities) shouldBe QuickToggleFieldState.Idle(true)
    }

    @Test
    fun `a helper that cannot change don't keep activities reports why and keeps the last value`() {
        val h = Harness()
        h.scope.advanceUntilIdle()
        h.activityManager.failure = DisplaySettingRead.PermissionDenied("java.lang.SecurityException: Permission Denial")

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.DontKeepActivities, true))
        h.scope.advanceUntilIdle()

        h.toggle(DeveloperToggle.DontKeepActivities) shouldBe
            QuickToggleFieldState.Error("java.lang.SecurityException: Permission Denial", false)
        h.devices.getValue(serialA).alwaysFinish shouldBe false
    }

    @Test
    fun `turning surface updates off when they are already off sends no toggling write`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.ShowSurfaceUpdates, false))
        h.scope.advanceUntilIdle()

        h.transport.commands shouldNotContain "service call SurfaceFlinger 1002 i32 0"
        h.toggle(DeveloperToggle.ShowSurfaceUpdates) shouldBe QuickToggleFieldState.Idle(false)
    }

    @Test
    fun `surface updates on then off ends off even though the off write toggles`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.ShowSurfaceUpdates, true))
        h.scope.advanceUntilIdle()
        h.toggle(DeveloperToggle.ShowSurfaceUpdates) shouldBe QuickToggleFieldState.Idle(true)

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.ShowSurfaceUpdates, false))
        h.scope.advanceUntilIdle()

        h.devices.getValue(serialA).surfaceUpdates shouldBe false
        h.toggle(DeveloperToggle.ShowSurfaceUpdates) shouldBe QuickToggleFieldState.Idle(false)
    }

    @Test
    fun `a device that rejects SurfaceFlinger calls explains that root is needed`() {
        val h = Harness(mapOf(serialA to FakeDevice(surfaceFlingerAllowed = false)))
        h.scope.advanceUntilIdle()

        h.toggle(DeveloperToggle.ShowSurfaceUpdates) shouldBe
            QuickToggleFieldState.Error(ShowSurfaceUpdatesCommand.NEEDS_ROOT_MESSAGE, null)

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.ShowSurfaceUpdates, true))
        h.scope.advanceUntilIdle()

        h.toggle(DeveloperToggle.ShowSurfaceUpdates) shouldBe
            QuickToggleFieldState.Error(ShowSurfaceUpdatesCommand.NEEDS_ROOT_MESSAGE, null)
    }

    @Test
    fun `setting a process limit reports the limit the device confirms`() {
        val h = Harness()
        h.scope.advanceUntilIdle()

        h.viewModel.handle(DeveloperOptionsIntent.SetProcessLimit(0))
        h.scope.advanceUntilIdle()
        h.viewModel.state.value.processLimit shouldBe QuickToggleFieldState.Idle(0)

        h.viewModel.handle(DeveloperOptionsIntent.SetProcessLimit(BackgroundProcessLimit.STANDARD))
        h.scope.advanceUntilIdle()
        h.viewModel.state.value.processLimit shouldBe QuickToggleFieldState.Idle(BackgroundProcessLimit.STANDARD)
        h.activityManager.actions.last() shouldBe (serialA to DevOptionsAction.SetProcessLimit(-1))
    }

    @Test
    fun `a helper failure keeps the last confirmed limit next to the error`() {
        val h = Harness(mapOf(serialA to FakeDevice(processLimit = 3)))
        h.scope.advanceUntilIdle()
        h.activityManager.failure = DisplaySettingRead.Malformed(raw = "", reason = "helper did not start")

        h.viewModel.handle(DeveloperOptionsIntent.SetProcessLimit(1))
        h.scope.advanceUntilIdle()

        h.viewModel.state.value.processLimit shouldBe QuickToggleFieldState.Error("Device helper failed: helper did not start", 3)
    }

    @Test
    fun `a device switch mid-write drops the stale readback`() {
        val h = Harness(mapOf(serialA to FakeDevice(), serialB to FakeDevice(stayOnMask = 0)))
        h.scope.advanceUntilIdle()
        h.transport.delays["svc power stayon true"] = 1_000

        h.viewModel.handle(DeveloperOptionsIntent.SetToggle(DeveloperToggle.StayAwake, true))
        h.scope.testScheduler.advanceTimeBy(500)
        h.selected.value = online(serialB)
        h.scope.advanceUntilIdle()

        h.toggle(DeveloperToggle.StayAwake) shouldBe QuickToggleFieldState.Idle(false)
    }

    private companion object {
        fun online(serial: DeviceSerial) =
            SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))
    }
}
