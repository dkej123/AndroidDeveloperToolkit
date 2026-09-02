package dev.acme.adbtoolbox.domain.process

/** How a process's execution ended. Never fabricated — an unknown exit is never coerced to 0/-1. */
sealed interface ProcessOutcome {
    data class Completed(val exitCode: Int) : ProcessOutcome

    data object TimedOut : ProcessOutcome

    data class StartFailure(val reason: String) : ProcessOutcome
}
