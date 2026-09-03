package dev.acme.adbtoolbox.application.feedback

import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator

/** User/system-triggered inputs [FeedbackViewModel] reduces against [FeedbackViewState] (ADR 0004). */
sealed interface FeedbackIntent {
    /** Enqueues [message] as a new toast, bounded and expired per [FeedbackViewModel]'s policy. */
    data class Post(val message: FeedbackMessage) : FeedbackIntent

    /** Explicit manual dismissal of the toast identified by [id] — any severity, including [dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity.Error]. */
    data class Dismiss(val id: String) : FeedbackIntent

    /** Invokes the [dev.acme.adbtoolbox.domain.feedback.FeedbackAction] on the toast identified by [id], then dismisses it. A no-op if that toast has no action or no longer exists. */
    data class InvokeAction(val id: String) : FeedbackIntent

    /** Shows or clears the "command in progress" indicator mirrored into [FeedbackViewState.status]. */
    data class SetProcess(val indicator: ProcessIndicator) : FeedbackIntent
}
