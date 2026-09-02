package dev.acme.adbtoolbox.domain.process

/**
 * One event from a running process's output stream. A [ProcessRequest] with
 * [ProcessOutputKind.Text] emits only [StdoutText]/[StderrText]; [ProcessOutputKind.Binary] emits
 * only [StdoutBytes]/[StderrBytes] — the two families are never mixed for one request. Every
 * execution emits exactly one terminal [Completed] event.
 */
sealed interface ProcessEvent {
    data class StdoutText(val line: String) : ProcessEvent

    data class StderrText(val line: String) : ProcessEvent

    data class StdoutBytes(val bytes: ByteArray) : ProcessEvent

    data class StderrBytes(val bytes: ByteArray) : ProcessEvent

    data class Completed(val outcome: ProcessOutcome) : ProcessEvent
}
