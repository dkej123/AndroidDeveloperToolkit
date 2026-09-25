package dev.acme.adbtoolbox.application.devicebar

import dev.acme.adbtoolbox.application.device.SelectedDeviceIntent
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.DeviceListRefresher
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task 011's device bar and picker presenter: maps [selectedDeviceViewModel]'s
 * [SelectedDeviceState] and [deviceRepository]'s live device list to [DeviceBarViewState], and
 * reduces mouse/keyboard/refresh intents against it. Never selects by name or list index — every
 * selection this class issues to [selectedDeviceViewModel] is an explicit
 * [SelectedDeviceIntent.Select] carrying an exact [DeviceSerial] taken straight from a
 * [DevicePickerItem] (mouse click) or from the currently highlighted picker row (keyboard Enter).
 *
 * [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-injection seam as
 * every other ViewModel in this module (ADR 0004); [scope] is owned by the caller.
 *
 * **Refresh suppression**: [_isRefreshing] gates [DeviceBarIntent.Refresh] — a request that arrives
 * while a previous one is still awaiting [refresher] is a silent no-op, never a second concurrent
 * call. [refresher] itself is a domain port ([DeviceListRefresher]), so this reduces to plain
 * suspend-function orchestration testable with a fake, with no hardware/adb dependency.
 *
 * **Pair over Wi-Fi**: [DeviceBarIntent.RequestPairOverWifi] only emits on [pairOverWifiRequests] —
 * task 039 owns the actual pairing flow; this class deliberately invents none of it.
 */
class DeviceBarViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val deviceRepository: DeviceRepository,
    private val selectedDeviceViewModel: SelectedDeviceViewModel,
    private val refresher: DeviceListRefresher,
) {
    private val _pickerOpen = MutableStateFlow(false)
    private val _highlightedIndex = MutableStateFlow(-1)
    private val _isRefreshing = MutableStateFlow(false)

    private val _pairOverWifiRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once per [DeviceBarIntent.RequestPairOverWifi] — see the class doc's Pair-over-Wi-Fi note. */
    val pairOverWifiRequests: SharedFlow<Unit> = _pairOverWifiRequests.asSharedFlow()

    private val discovery = combine(deviceRepository.devices, deviceRepository.listError, ::Discovery)

    val state: StateFlow<DeviceBarViewState> = combine(
        selectedDeviceViewModel.state,
        discovery,
        _pickerOpen,
        _highlightedIndex,
        _isRefreshing,
        ::reduce,
    ).stateIn(scope, SharingStarted.Eagerly, DeviceBarViewState())

    fun handle(intent: DeviceBarIntent) {
        when (intent) {
            DeviceBarIntent.OpenPicker -> openPicker()
            DeviceBarIntent.ClosePicker -> closePicker()
            is DeviceBarIntent.HighlightAt -> _highlightedIndex.value = intent.index
            DeviceBarIntent.ConfirmHighlighted -> confirmHighlighted()
            is DeviceBarIntent.SelectDevice -> selectDevice(intent.serial)
            DeviceBarIntent.Refresh -> refresh()
            DeviceBarIntent.RequestPairOverWifi -> _pairOverWifiRequests.tryEmit(Unit)
        }
    }

    private fun openPicker() {
        val items = state.value.picker.items
        val selectedIndex = items.indexOfFirst { it.isSelected }
        _highlightedIndex.value = when {
            selectedIndex >= 0 -> selectedIndex
            items.isNotEmpty() -> 0
            else -> -1
        }
        _pickerOpen.value = true
    }

    private fun closePicker() {
        _pickerOpen.value = false
        _highlightedIndex.value = -1
    }

    private fun confirmHighlighted() {
        val picker = state.value.picker
        val item = picker.items.getOrNull(picker.highlightedIndex) ?: return
        selectDevice(item.serial)
    }

    private fun selectDevice(serial: DeviceSerial) {
        selectedDeviceViewModel.handle(SelectedDeviceIntent.Select(serial))
        closePicker()
    }

    private fun refresh() {
        if (_isRefreshing.value) return
        scope.launch(dispatchers.io) {
            _isRefreshing.value = true
            try {
                refresher.refresh()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private data class Discovery(val devices: List<Device>, val listError: String?)

    private fun reduce(
        selected: SelectedDeviceState,
        discovery: Discovery,
        pickerOpen: Boolean,
        highlighted: Int,
        refreshing: Boolean,
    ): DeviceBarViewState {
        val devices = discovery.devices
        val selectedSerial = selectedSerialOf(selected)
        val items = devices.map { device ->
            DevicePickerItem(
                serial = device.serial,
                // Human-readable (spaces restored); null still means "adb reported no model".
                model = device.model?.let { device.displayName },
                product = device.product,
                connectionKind = device.connectionKind,
                connectionState = device.state,
                isSelected = device.serial == selectedSerial,
            )
        }
        val clampedHighlight = if (items.isEmpty()) -1 else highlighted.coerceIn(-1, items.size - 1)
        val bar = when (selected) {
            SelectedDeviceState.Loading -> DeviceBarPresentation.Loading
            // "No device connected" only when adb really reported none; a failing query is shown
            // as such, and attached-but-unselected devices invite the user to pick one.
            SelectedDeviceState.None -> when {
                devices.isNotEmpty() -> DeviceBarPresentation.SelectDevice(deviceCount = devices.size)
                discovery.listError != null -> DeviceBarPresentation.Error(discovery.listError)
                else -> DeviceBarPresentation.NoDevice
            }
            is SelectedDeviceState.Online ->
                DeviceBarPresentation.Online(selected.device, onlineCount = devices.count { it.state == DeviceConnectionState.Online })
            is SelectedDeviceState.Unauthorized -> DeviceBarPresentation.Unauthorized(selected.device)
            is SelectedDeviceState.Offline -> DeviceBarPresentation.Offline(selected.device)
            is SelectedDeviceState.Ineligible ->
                DeviceBarPresentation.Error("Device ${selected.device.serial} is in an unsupported connection state")
            is SelectedDeviceState.Disconnected -> DeviceBarPresentation.Error("Device ${selected.serial} disconnected")
            is SelectedDeviceState.Error -> DeviceBarPresentation.Error(selected.message)
        }
        return DeviceBarViewState(
            bar = bar,
            picker = DevicePickerState(isOpen = pickerOpen, items = items, highlightedIndex = clampedHighlight),
            isRefreshing = refreshing,
        )
    }

    private fun selectedSerialOf(state: SelectedDeviceState): DeviceSerial? = when (state) {
        is SelectedDeviceState.Online -> state.device.serial
        is SelectedDeviceState.Unauthorized -> state.device.serial
        is SelectedDeviceState.Offline -> state.device.serial
        is SelectedDeviceState.Ineligible -> state.device.serial
        is SelectedDeviceState.Disconnected -> state.serial
        else -> null
    }
}
