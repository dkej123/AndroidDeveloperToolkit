package dev.acme.adbtoolbox.application.locale

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.location.EmulatorLocationCommand
import dev.acme.adbtoolbox.domain.location.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Location (design §3c). The emulator has no readback, so [current] is the last fix set in this session. */
data class LocationViewState(
    val emulator: Boolean = false,
    val current: Pair<String?, GeoPoint>? = null,
    val applying: Boolean = false,
    val latitudeError: String? = null,
    val longitudeError: String? = null,
)

sealed interface LocationIntent {
    data class Preset(val name: String) : LocationIntent

    data class Custom(val latitude: String, val longitude: String) : LocationIntent
}

/**
 * Location (task 064, design §3c): city presets and a custom latitude/longitude, sent as an emulator
 * GPS fix. Physical devices and emulators without a console are not emulators here.
 */
class LocationViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val useCase: EmulatorLocationUseCase,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val onResult: (String, Boolean) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(LocationViewState())
    val state: StateFlow<LocationViewState> = _state.asStateFlow()
    private val context = MutableStateFlow(selectedDeviceState.value.toCommandContext())
    private val lastFix = mutableMapOf<DeviceSerial, Pair<String?, GeoPoint>>()

    init {
        scope.launch(dispatchers.default) {
            selectedDeviceState.map { it.toCommandContext() }.distinctUntilChanged().collect { next ->
                context.value = next
                val serial = (next as? DeviceCommandContext.Eligible)?.serial
                _state.value = LocationViewState(
                    emulator = serial != null && EmulatorLocationCommand.isSupported(serial),
                    current = serial?.let(lastFix::get),
                )
            }
        }
    }

    fun handle(intent: LocationIntent) {
        val serial = (context.value as? DeviceCommandContext.Eligible)?.serial ?: return
        when (intent) {
            is LocationIntent.Preset -> {
                val city = EmulatorLocationCommand.cities.firstOrNull { it.name == intent.name } ?: return
                send(serial, city.name, city.point)
            }
            is LocationIntent.Custom -> {
                val latitude = intent.latitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                val longitude = intent.longitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                _state.update {
                    it.copy(
                        latitudeError = if (latitude == null) "Latitude must be −90 to 90" else null,
                        longitudeError = if (longitude == null) "Longitude must be −180 to 180" else null,
                    )
                }
                if (latitude != null && longitude != null) send(serial, null, GeoPoint.of(latitude, longitude)!!)
            }
        }
    }

    private fun send(serial: DeviceSerial, name: String?, point: GeoPoint) {
        _state.update { it.copy(applying = true) }
        scope.launch(dispatchers.io) {
            val result = useCase.set(serial, point)
            val current = (context.value as? DeviceCommandContext.Eligible)?.serial == serial
            val coordinates = "${format(point.latitude)}, ${format(point.longitude)}"
            when (result) {
                is LocationResult.Set -> {
                    lastFix[serial] = name to point
                    if (current) _state.update { it.copy(applying = false, current = name to point) }
                    onResult("Location set to ${name?.let { "$it ($coordinates)" } ?: coordinates}", true)
                }
                LocationResult.NotAnEmulator -> {
                    if (current) _state.update { it.copy(applying = false) }
                    onResult("Emulator only — a physical device reports its real GPS", false)
                }
                is LocationResult.Failed -> {
                    if (current) _state.update { it.copy(applying = false) }
                    onResult("Couldn’t set the location: ${result.reason}", false)
                }
            }
        }
    }

    companion object {
        /** Four decimals, as the design shows coordinates ("52.2297, 21.0122"). */
        fun format(value: Double): String {
            val scaled = kotlin.math.round(kotlin.math.abs(value) * 10_000).toLong()
            val sign = if (value < 0 && scaled != 0L) "-" else ""
            return "$sign${scaled / 10_000}.${(scaled % 10_000).toString().padStart(4, '0')}"
        }
    }
}
