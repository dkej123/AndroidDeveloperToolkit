package dev.acme.adbtoolbox.domain.feedback

/**
 * Severity vocabulary for the one non-modal feedback channel (task 013). Matches
 * `design/README.md`'s Interactions & behaviour section and `tokens/tokens.json`'s severity
 * colors: [Success] (green, auto-expires), [Error] (red, "persists" until dismissed/fixed,
 * carries the single recovery action), [Warning] (amber — a non-default/override-adjacent
 * condition that is not yet an error), and [Info] (neutral, auto-expires) for messages that are
 * neither success nor failure. Only [Error] is exempt from auto-expiry — see
 * [dev.acme.adbtoolbox.application.feedback.FeedbackViewModel].
 */
enum class FeedbackSeverity {
    Info,
    Success,
    Warning,
    Error,
}
