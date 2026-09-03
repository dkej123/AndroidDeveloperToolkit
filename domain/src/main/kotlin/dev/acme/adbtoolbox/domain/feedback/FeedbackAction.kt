package dev.acme.adbtoolbox.domain.feedback

/**
 * The optional SINGLE recovery/fix action a [FeedbackMessage] may carry — `design/README.md`:
 * "error ... carries the single action that fixes it (e.g. 'scrcpy not found on PATH' -> Set
 * path...)". [FeedbackMessage.action] is a single nullable value, never a list, so this type
 * cannot represent more than one action by construction.
 */
data class FeedbackAction(
    val label: String,
    val invoke: () -> Unit,
)
