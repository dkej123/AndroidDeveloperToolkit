package dev.acme.adbtoolbox.application.capture

/** User-triggered inputs [CaptureViewModel] reduces against [CaptureViewState] (ADR 0004). */
sealed interface CaptureIntent {
    /** Captures one screenshot from the currently eligible device, if any. */
    data object CaptureScreenshot : CaptureIntent

    /** Reveals the last successfully captured file, if any. */
    data object RevealLastCapture : CaptureIntent
}
