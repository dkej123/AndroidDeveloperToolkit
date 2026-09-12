package dev.acme.adbtoolbox.domain.recording

/** The result of one `start(serial)` request against a recording session manager. */
sealed interface RecordingStartOutcome {

    /** A new session was launched (transitioned to [RecordingSessionState.Starting]). */
    data object Started : RecordingStartOutcome

    /** Rejected without touching any existing remote process — a session for that serial was already
     * [RecordingSessionState.Starting], [RecordingSessionState.Recording],
     * [RecordingSessionState.Stopping], or [RecordingSessionState.Pulling]; starting again would have
     * silently raced a second owned `screenrecord` process (or pull) for the same device. */
    data object Rejected : RecordingStartOutcome
}
