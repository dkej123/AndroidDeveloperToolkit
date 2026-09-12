package dev.acme.adbtoolbox.domain.device

import kotlinx.coroutines.CompletableDeferred

/**
 * A controllable [DeviceListRefresher] test double: [refresh] suspends until [complete] is called,
 * so tests can deterministically observe an in-flight refresh (task 011's duplicate-refresh
 * suppression) instead of racing a real coroutine completion.
 */
class FakeDeviceListRefresher : DeviceListRefresher {

    var callCount: Int = 0
        private set

    private var gate = CompletableDeferred<Unit>()

    override suspend fun refresh() {
        callCount++
        gate.await()
    }

    /** Lets the current in-flight [refresh] call (and resets the gate for the next one) return. */
    fun complete() {
        val current = gate
        gate = CompletableDeferred()
        current.complete(Unit)
    }
}
