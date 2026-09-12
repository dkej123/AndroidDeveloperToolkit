package dev.acme.adbtoolbox.domain.deviceactions

/**
 * Which of `design/README.md` §3's three Device-view basic actions is being requested/in flight.
 * Shared by the Reboot/Wake use case and the Open-shell use case so a presenter's duplicate-in-
 * flight guard (task 016) has one vocabulary to key off of, rather than three unrelated flags.
 */
enum class DeviceActionKind {
    Reboot,
    OpenShell,
    Wake,
}
