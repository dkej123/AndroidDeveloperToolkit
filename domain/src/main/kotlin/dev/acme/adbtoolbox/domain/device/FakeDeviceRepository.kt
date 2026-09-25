package dev.acme.adbtoolbox.domain.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A deterministic [DeviceRepository] test double: [devices] is driven directly by test code via
 * [emit] instead of a real `adb devices -l` refresh loop (mirrors [dev.acme.adbtoolbox.domain.adb.FakeAdbTransport]).
 */
class FakeDeviceRepository(initial: List<Device> = emptyList()) : DeviceRepository {
    private val _devices = MutableStateFlow(initial)
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _listError = MutableStateFlow<String?>(null)
    override val listError: StateFlow<String?> = _listError.asStateFlow()

    fun emit(devices: List<Device>) {
        _devices.value = devices
    }

    fun emitListError(message: String?) {
        _listError.value = message
    }
}
