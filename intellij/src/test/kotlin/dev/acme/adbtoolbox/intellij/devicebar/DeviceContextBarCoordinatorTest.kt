package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewState
import dev.acme.adbtoolbox.application.devicebar.DevicePickerItem
import dev.acme.adbtoolbox.application.devicebar.DevicePickerState
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceListRefresher
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive

/**
 * Connects task 011's [DeviceBarViewModel] to task 010's [AdbToolboxHostPanel] via
 * [DeviceContextBarCoordinator]. Kept as a [BasePlatformTestCase] like every other test in this
 * module. Exercises [DeviceContextBarCoordinator.render] directly against constructed
 * [DeviceBarViewState] values — the same "invoke the handler directly" pattern
 * [dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinatorTest] documents for this
 * headless sandbox.
 */
class DeviceContextBarCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private fun viewModel(dispatchers: DispatcherProvider, scope: CoroutineScope): DeviceBarViewModel {
        val repository = FakeDeviceRepository()
        val selectedDeviceViewModel = SelectedDeviceViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = repository,
            persistence = FakeDeviceSelectionPersistence(),
        )
        return DeviceBarViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = repository,
            selectedDeviceViewModel = selectedDeviceViewModel,
            refresher = FakeDeviceListRefresher(),
        )
    }

    private fun coordinator(host: AdbToolboxHostPanel): DeviceContextBarCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        return DeviceContextBarCoordinator(host, viewModel(dispatchers, scope), scope, dispatchers)
    }

    fun `test construction mounts the bar panel into the device context slot`() {
        val host = AdbToolboxHostPanel()

        val coordinator = coordinator(host)

        assertTrue(host.deviceContextSlot.components.contains(coordinator.barPanel))
        coordinator.dispose()
    }

    fun `test construction does not show the picker overlay until it is opened`() {
        val host = AdbToolboxHostPanel()
        val before = host.overlays.overlayCount

        coordinator(host)

        assertEquals(before, host.overlays.overlayCount)
    }

    private fun item(serial: String) = DevicePickerItem(
        serial = DeviceSerial.of(serial),
        model = "Pixel_5",
        product = null,
        connectionKind = DeviceConnectionKind.of(serial),
        connectionState = DeviceConnectionState.Online,
        isSelected = false,
    )

    fun `test rendering a state with the picker open shows exactly one overlay and updates both panels`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        val before = host.overlays.overlayCount

        coordinator.render(
            DeviceBarViewState(
                bar = DeviceBarPresentation.NoDevice,
                picker = DevicePickerState(isOpen = true, items = listOf(item("AAA"))),
            ),
        )

        assertEquals(before + 1, host.overlays.overlayCount)
        assertTrue(coordinator.barPanel.selectorText.contains("No device", ignoreCase = true))
        coordinator.dispose()
    }

    fun `test rendering a state with the picker closed dismisses the overlay`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        coordinator.render(DeviceBarViewState(picker = DevicePickerState(isOpen = true, items = listOf(item("AAA")))))
        val whileOpen = host.overlays.overlayCount

        coordinator.render(DeviceBarViewState(picker = DevicePickerState(isOpen = false)))

        assertEquals(whileOpen - 1, host.overlays.overlayCount)
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope and dismisses the picker overlay`() {
        val host = AdbToolboxHostPanel()
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val coordinator = DeviceContextBarCoordinator(host, viewModel(dispatchers, scope), scope, dispatchers)
        coordinator.render(DeviceBarViewState(picker = DevicePickerState(isOpen = true, items = listOf(item("AAA")))))

        coordinator.dispose()

        assertFalse(scope.isActive)
        assertEquals(0, host.overlays.overlayCount)
    }

    fun `test disposing the host also removes the coordinator's picker overlay`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        coordinator.render(DeviceBarViewState(picker = DevicePickerState(isOpen = true, items = listOf(item("AAA")))))

        host.dispose()

        assertEquals(0, host.overlays.overlayCount)
        coordinator.dispose()
    }

    // ---- Task 050: opening/closing the picker via keyboard must move focus with it ----

    fun `test opening the picker moves keyboard focus into the row list exactly once`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        val openState = DeviceBarViewState(picker = DevicePickerState(isOpen = true, items = listOf(item("AAA"))))

        coordinator.render(openState)
        // A second render while still open (e.g. a highlight change) must not re-steal focus from
        // whatever row the user has since navigated to with the arrow keys.
        coordinator.render(openState.copy(picker = openState.picker.copy(highlightedIndex = 0)))

        assertEquals(1, coordinator.pickerPanelForTest.focusListCallCountForTest)
        coordinator.dispose()
    }

    fun `test closing the picker returns keyboard focus to the selector button`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        coordinator.render(DeviceBarViewState(picker = DevicePickerState(isOpen = true, items = listOf(item("AAA")))))

        coordinator.render(DeviceBarViewState(picker = DevicePickerState(isOpen = false)))

        assertEquals(1, coordinator.barPanel.focusSelectorCallCountForTest)
        coordinator.dispose()
    }

    fun `test a state that never opens the picker never touches focus`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)

        coordinator.render(DeviceBarViewState(bar = DeviceBarPresentation.NoDevice))

        assertEquals(0, coordinator.pickerPanelForTest.focusListCallCountForTest)
        assertEquals(0, coordinator.barPanel.focusSelectorCallCountForTest)
        coordinator.dispose()
    }

    fun `test the coordinator wires up against a real view model without a construction-time crash`() {
        val host = AdbToolboxHostPanel()
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

        val coordinator = DeviceContextBarCoordinator(host, viewModel(dispatchers, scope), scope, dispatchers)

        assertNotNull(coordinator.barPanel)
        coordinator.dispose()
    }
}
