package dev.acme.adbtoolbox.domain.device

/**
 * A manual "refresh now" trigger for [DeviceRepository]'s live device list (task 011's device-bar
 * refresh control), kept as its own narrow port distinct from [DeviceRepository]'s always-on
 * poll/hotplug refresh loop (task 008, ADR 0005): this is only ever invoked by an explicit user
 * action (the device bar's refresh button/shortcut), never inferred or scheduled automatically.
 */
fun interface DeviceListRefresher {
    suspend fun refresh()
}
