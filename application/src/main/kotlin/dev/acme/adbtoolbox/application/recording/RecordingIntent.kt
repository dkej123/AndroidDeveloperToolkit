package dev.acme.adbtoolbox.application.recording

/**
 * The single user-triggered input [RecordingViewModel] reduces against (ADR 0004), mirroring task
 * 018's [dev.acme.adbtoolbox.application.mirroring.MirroringIntent.Toggle]: the Device-view
 * "Record"/"Stop & save" control forwards both directions of the toggle to the exact same
 * [RecordingViewModel.handle] entry point, so start-vs-stop can never diverge into two code paths.
 */
sealed interface RecordingIntent {
    data object Toggle : RecordingIntent

    /** Reveals the last successfully saved recording, if any. */
    data object RevealLastRecording : RecordingIntent
}
