@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.devicebar

import dev.acme.adbtoolbox.application.device.SelectedDeviceIntent
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceListRefresher
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serialUsbA = DeviceSerial.of("R58N90ABCDE")
private val serialUsbB = DeviceSerial.of("R58N90FGHIJ")
private val serialWifi = DeviceSerial.of("192.168.1.42:5555")

private fun device(
    serial: DeviceSerial,
    state: DeviceConnectionState = DeviceConnectionState.Online,
    model: String? = "Pixel_5",
) = Device(serial = serial, state = state, model = model)

private class Harness(
    val scope: TestScope = TestScope(),
    val repository: FakeDeviceRepository = FakeDeviceRepository(),
    val refresher: FakeDeviceListRefresher = FakeDeviceListRefresher(),
) {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val dispatchers = TestDispatcherProviderFixture(dispatcher)
    val selectedDeviceViewModel = SelectedDeviceViewModel(
        scope = scope,
        dispatchers = dispatchers,
        deviceRepository = repository,
        persistence = FakeDeviceSelectionPersistence(),
    )
    val viewModel = DeviceBarViewModel(
        scope = scope,
        dispatchers = dispatchers,
        deviceRepository = repository,
        selectedDeviceViewModel = selectedDeviceViewModel,
        refresher = refresher,
    )

    fun settle() {
        scope.advanceTimeBy(1)
        scope.runCurrent()
    }
}

class DeviceBarViewModelTest {

    @Test
    fun `initial state is Loading before selection restore resolves`() {
        val harness = Harness()

        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.Loading
    }

    @Test
    fun `no selection with several devices present asks the user to pick one instead of reporting no device`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()

        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.SelectDevice(deviceCount = 2)
    }

    @Test
    fun `no devices and no adb failure maps to NoDevice`() = runTest {
        val harness = Harness()
        harness.settle()

        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.NoDevice
    }

    @Test
    fun `a failing device list query is surfaced instead of reporting no device`() = runTest {
        val harness = Harness()
        harness.settle()

        harness.repository.emitListError("adb executable not found (tried: PathFallback)")
        harness.settle()

        harness.viewModel.state.value.bar shouldBe
            DeviceBarPresentation.Error("adb executable not found (tried: PathFallback)")

        harness.repository.emitListError(null)
        harness.settle()

        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.NoDevice
    }

    @Test
    fun `an online selected device maps to Online with the current online count`() = runTest {
        val harness = Harness(
            repository = FakeDeviceRepository(
                listOf(device(serialUsbA), device(serialUsbB, DeviceConnectionState.Offline)),
            ),
        )
        harness.settle()

        harness.selectedDeviceViewModel.handle(SelectedDeviceIntent.Select(serialUsbA))
        harness.settle()

        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.Online(device(serialUsbA), onlineCount = 1)
    }

    @Test
    fun `an unauthorized selected device maps to Unauthorized, never coerced to Online`() = runTest {
        val harness = Harness(
            repository = FakeDeviceRepository(listOf(device(serialUsbA, DeviceConnectionState.Unauthorized))),
        )
        harness.settle()

        harness.selectedDeviceViewModel.handle(SelectedDeviceIntent.Select(serialUsbA))
        harness.settle()

        harness.viewModel.state.value.bar shouldBe
            DeviceBarPresentation.Unauthorized(device(serialUsbA, DeviceConnectionState.Unauthorized))
    }

    @Test
    fun `an offline selected device maps to Offline, never treated as command-eligible`() = runTest {
        val harness = Harness(
            repository = FakeDeviceRepository(listOf(device(serialUsbA, DeviceConnectionState.Offline))),
        )
        harness.settle()

        harness.selectedDeviceViewModel.handle(SelectedDeviceIntent.Select(serialUsbA))
        harness.settle()

        harness.viewModel.state.value.bar shouldBe
            DeviceBarPresentation.Offline(device(serialUsbA, DeviceConnectionState.Offline))
    }

    @Test
    fun `a disconnected selected device maps to Error rather than a silent switch`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA))))
        harness.settle()
        harness.selectedDeviceViewModel.handle(SelectedDeviceIntent.Select(serialUsbA))
        harness.settle()

        harness.repository.emit(emptyList())
        harness.settle()

        (harness.viewModel.state.value.bar is DeviceBarPresentation.Error) shouldBe true
    }

    @Test
    fun `picker items are keyed by exact serial, distinguishing duplicate model names and a wifi serial`() = runTest {
        val harness = Harness(
            repository = FakeDeviceRepository(
                listOf(
                    device(serialUsbA, model = "Pixel_5"),
                    device(serialUsbB, model = "Pixel_5"),
                    device(serialWifi, model = "Pixel_5"),
                ),
            ),
        )
        harness.settle()

        val items = harness.viewModel.state.value.picker.items
        items.map { it.serial } shouldBe listOf(serialUsbA, serialUsbB, serialWifi)
        items.map { it.connectionKind } shouldBe
            listOf(DeviceConnectionKind.Usb, DeviceConnectionKind.Usb, DeviceConnectionKind.Wifi)
    }

    @Test
    fun `opening the picker highlights the currently selected device`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()
        harness.selectedDeviceViewModel.handle(SelectedDeviceIntent.Select(serialUsbB))
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.OpenPicker)
        harness.settle()

        val picker = harness.viewModel.state.value.picker
        picker.isOpen shouldBe true
        picker.highlightedIndex shouldBe 1
        picker.items[1].isSelected shouldBe true
    }

    @Test
    fun `opening the picker with no selection highlights the first row`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.OpenPicker)
        harness.settle()

        harness.viewModel.state.value.picker.highlightedIndex shouldBe 0
    }

    @Test
    fun `Up Down highlight navigation moves the highlighted index without selecting`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()
        harness.viewModel.handle(DeviceBarIntent.OpenPicker)
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.HighlightAt(1))
        harness.settle()

        harness.viewModel.state.value.picker.highlightedIndex shouldBe 1
        harness.selectedDeviceViewModel.state.value shouldBe
            dev.acme.adbtoolbox.domain.device.SelectedDeviceState.None
    }

    @Test
    fun `Enter applies the highlighted device by its exact serial and closes the picker`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()
        harness.viewModel.handle(DeviceBarIntent.OpenPicker)
        harness.settle()
        harness.viewModel.handle(DeviceBarIntent.HighlightAt(1))
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.ConfirmHighlighted)
        harness.settle()

        harness.viewModel.state.value.picker.isOpen shouldBe false
        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.Online(device(serialUsbB), onlineCount = 2)
    }

    @Test
    fun `Escape closes the picker without changing selection`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()
        harness.viewModel.handle(DeviceBarIntent.OpenPicker)
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.ClosePicker)
        harness.settle()

        harness.viewModel.state.value.picker.isOpen shouldBe false
        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.SelectDevice(deviceCount = 2)
    }

    @Test
    fun `mouse selection of an exact serial applies it directly without needing to highlight first`() = runTest {
        val harness = Harness(repository = FakeDeviceRepository(listOf(device(serialUsbA), device(serialUsbB))))
        harness.settle()
        harness.viewModel.handle(DeviceBarIntent.OpenPicker)
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.SelectDevice(serialUsbB))
        harness.settle()

        harness.viewModel.state.value.bar shouldBe DeviceBarPresentation.Online(device(serialUsbB), onlineCount = 2)
        harness.viewModel.state.value.picker.isOpen shouldBe false
    }

    @Test
    fun `refresh triggers the injected refresher exactly once`() = runTest {
        val harness = Harness()

        harness.viewModel.handle(DeviceBarIntent.Refresh)
        harness.settle()
        harness.refresher.complete()
        harness.settle()

        harness.refresher.callCount shouldBe 1
    }

    @Test
    fun `a second refresh request while one is in flight is suppressed`() = runTest {
        val harness = Harness()

        harness.viewModel.handle(DeviceBarIntent.Refresh)
        harness.settle()
        harness.viewModel.state.value.isRefreshing shouldBe true

        harness.viewModel.handle(DeviceBarIntent.Refresh)
        harness.settle()

        harness.refresher.callCount shouldBe 1

        harness.refresher.complete()
        harness.settle()
        harness.viewModel.state.value.isRefreshing shouldBe false
    }

    @Test
    fun `a refresh request after a previous one completed is allowed again`() = runTest {
        val harness = Harness()

        harness.viewModel.handle(DeviceBarIntent.Refresh)
        harness.settle()
        harness.refresher.complete()
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.Refresh)
        harness.settle()

        harness.refresher.callCount shouldBe 2
        harness.refresher.complete()
    }

    @Test
    fun `requesting pair-over-wifi emits an event without inventing the pairing flow`() = runTest {
        val harness = Harness()
        val events = mutableListOf<Unit>()
        val collectJob = harness.scope.launch {
            harness.viewModel.pairOverWifiRequests.collect { events += it }
        }
        harness.settle()

        harness.viewModel.handle(DeviceBarIntent.RequestPairOverWifi)
        harness.settle()

        events.size shouldBe 1
        collectJob.cancel()
    }
}
