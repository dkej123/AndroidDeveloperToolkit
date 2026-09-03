package dev.acme.adbtoolbox.domain.feedback

/**
 * The persistent status-bar concept `design/README.md` describes separately from the ephemeral
 * toast log: "Every toast text is also written into the status-bar message" ([message]), plus the
 * status bar's running-process chip ([process]). Unlike a toast, [message] does not expire on its
 * own — it holds the last-posted text until replaced by the next one.
 */
data class StatusState(
    val message: String? = null,
    val process: ProcessIndicator = ProcessIndicator.Idle,
)
