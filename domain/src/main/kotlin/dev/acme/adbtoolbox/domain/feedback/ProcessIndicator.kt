package dev.acme.adbtoolbox.domain.feedback

/**
 * A "command in progress" indicator (task 013), distinct from a completed toast — e.g. the
 * status-bar's running-process chip (`design/README.md` §8) is one concrete later consumer of
 * this, but this type itself carries no feature-specific wording, only whether something is
 * running and, if so, its caller-supplied [InProgress.label].
 */
sealed interface ProcessIndicator {
    data object Idle : ProcessIndicator
    data class InProgress(val label: String) : ProcessIndicator
}
