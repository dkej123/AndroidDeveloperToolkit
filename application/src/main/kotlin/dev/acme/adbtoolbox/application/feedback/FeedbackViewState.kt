package dev.acme.adbtoolbox.application.feedback

import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.StatusState

/**
 * The immutable state of the feedback/status/toast channel (task 013, ADR 0004): [toasts] is the
 * bounded, ordered (oldest first) toast queue; [status] is the separate persistent status-bar
 * concept toast text mirrors into. Replaced wholesale on every reduction, never mutated in place.
 */
data class FeedbackViewState(
    val toasts: List<FeedbackMessage> = emptyList(),
    val status: StatusState = StatusState(),
)
