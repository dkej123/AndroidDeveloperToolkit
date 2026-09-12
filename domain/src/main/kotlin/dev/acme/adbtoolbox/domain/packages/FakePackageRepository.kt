package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A deterministic [PackageRepository] test double for downstream Apps/Logcat tests (task 021):
 * [state] is driven directly by test code via [emit] instead of a real discovery pipeline (mirrors
 * [dev.acme.adbtoolbox.domain.device.FakeDeviceRepository]). [refresh] calls are recorded rather
 * than acted on, so a test can assert a caller requested the expected serial/scope.
 */
class FakePackageRepository(initial: PackageListState = PackageListState.Loading) : PackageRepository {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PackageListState> = _state.asStateFlow()

    private val _refreshRequests = mutableListOf<Pair<DeviceSerial, PackageListScope>>()
    val refreshRequests: List<Pair<DeviceSerial, PackageListScope>> get() = _refreshRequests

    override fun refresh(serial: DeviceSerial, scope: PackageListScope) {
        _refreshRequests += serial to scope
    }

    fun emit(state: PackageListState) {
        _state.value = state
    }
}
