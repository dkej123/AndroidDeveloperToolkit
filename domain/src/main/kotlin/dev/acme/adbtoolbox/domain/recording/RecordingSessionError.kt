package dev.acme.adbtoolbox.domain.recording

/** Every way a screen-recording session can fail outright — always actionable/typed, never a bare
 * string collapsed into one generic error. */
sealed interface RecordingSessionError {

    /** The remote `screenrecord` command could not be started, or the transport call failed before
     * ever confirming the recording had begun. */
    data class StartFailure(val reason: String) : RecordingSessionError

    /**
     * The remote process stopped (requested or ADB's own maximum-duration auto-completion) but
     * retrieving its file afterward did not succeed — task 020's explicit partial-failure case
     * ("stop succeeds but pull fails"). [remoteFileRemaining] is true when the recording is still
     * believed to exist on the device (e.g. the pull itself failed/timed out rather than the device
     * going fully unreachable), so a caller can offer a truthful, recoverable story instead of
     * reporting an unrecoverable loss when the file may still be retrievable.
     */
    data class PullFailure(val reason: String, val remoteFileRemaining: Boolean) : RecordingSessionError
}
