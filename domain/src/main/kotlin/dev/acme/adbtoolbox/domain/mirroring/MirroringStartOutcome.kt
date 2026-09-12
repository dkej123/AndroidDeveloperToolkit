package dev.acme.adbtoolbox.domain.mirroring

/** The result of one `start(serial, options)` request against a mirroring session manager. */
sealed interface MirroringStartOutcome {

    /** A new session was launched (transitioned to [MirroringSessionState.Starting]). */
    data object Started : MirroringStartOutcome

    /** Rejected without touching any existing process — a session for that serial was already
     * [MirroringSessionState.Starting] or [MirroringSessionState.Running]; starting again would
     * have silently spawned a second owned `scrcpy` process for the same device. */
    data object Rejected : MirroringStartOutcome
}
