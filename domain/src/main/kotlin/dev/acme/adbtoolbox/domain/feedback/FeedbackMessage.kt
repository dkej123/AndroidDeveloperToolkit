package dev.acme.adbtoolbox.domain.feedback

/**
 * One entry in the non-modal feedback channel (task 013, `design/README.md`'s toast model):
 * [id] identifies it for dismissal/action-invocation; [severity] drives auto-expiry policy
 * ([dev.acme.adbtoolbox.application.feedback.FeedbackViewModel]); [action] is the optional single
 * recovery action ([FeedbackAction]). Feature-specific copy is supplied by the caller — this type
 * carries no wording of its own, per task 013's scope.
 */
data class FeedbackMessage(
    val id: String,
    val text: String,
    val severity: FeedbackSeverity,
    val action: FeedbackAction? = null,
)
