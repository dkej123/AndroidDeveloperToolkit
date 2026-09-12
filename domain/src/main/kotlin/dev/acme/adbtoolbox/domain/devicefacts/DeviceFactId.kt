package dev.acme.adbtoolbox.domain.devicefacts

/**
 * The closed set of device facts task 015 fetches for the Device view's facts grid
 * (`design/README.md` §3: Android, Resolution, Density, Battery, ABI, Uptime). Each fact is
 * fetched, parsed, and rendered independently — a failure resolving one must never blank the
 * others (task 015's acceptance criteria).
 */
enum class DeviceFactId {
    AndroidVersion,
    Resolution,
    Density,
    Battery,
    Abi,
    Uptime,
}
