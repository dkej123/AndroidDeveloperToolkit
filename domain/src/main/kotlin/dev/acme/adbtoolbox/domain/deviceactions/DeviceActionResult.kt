package dev.acme.adbtoolbox.domain.deviceactions

/**
 * What a task-016 device action (Reboot, Wake, or Open shell) produced — every failure path
 * (non-zero/unknown exit status, timeout, cancellation, transport/disconnect failure, shell-adapter
 * failure) is preserved as a caller-readable [Failure.reason] rather than collapsed into a boolean,
 * so the presenter can publish a truthful feedback message (task 016's scope: "preserve
 * errors/cancellation ... publish feedback").
 */
sealed interface DeviceActionResult {
    data object Success : DeviceActionResult
    data class Failure(val reason: String) : DeviceActionResult
}
