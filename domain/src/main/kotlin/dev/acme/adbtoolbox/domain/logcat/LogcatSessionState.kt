package dev.acme.adbtoolbox.domain.logcat

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

sealed interface LogcatSessionState {
    data object Idle : LogcatSessionState
    data class Starting(val serial: DeviceSerial) : LogcatSessionState
    data class Running(val serial: DeviceSerial) : LogcatSessionState
    data class Stopping(val serial: DeviceSerial) : LogcatSessionState
    data class Stopped(val serial: DeviceSerial?, val reason: LogcatStopReason) : LogcatSessionState
    data class Error(val serial: DeviceSerial?, val error: LogcatSessionError) : LogcatSessionState
}

enum class LogcatStopReason {
    Requested,
    DeviceUnavailable,
    DeviceChanged,
    Cancelled,
}

sealed interface LogcatSessionError {
    val diagnostics: String

    data class StartFailure(val reason: String, override val diagnostics: String = "") : LogcatSessionError
    data class UnexpectedExit(val exitCode: Int?, override val diagnostics: String = "") : LogcatSessionError
    data class StreamFailure(val reason: String, override val diagnostics: String = "") : LogcatSessionError
    data class TimedOut(override val diagnostics: String = "") : LogcatSessionError
}

sealed interface LogcatStartOutcome {
    data object Started : LogcatStartOutcome
    data object AlreadyActive : LogcatStartOutcome
    data object NoEligibleDevice : LogcatStartOutcome
}

data class LogcatBufferPublication(
    val cursor: LogcatBufferCursor,
    val entryCount: Int,
    val totalBytes: Int,
    val rejectedOversizeEntries: Long = 0,
)
