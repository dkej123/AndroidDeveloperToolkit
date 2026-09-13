package dev.acme.adbtoolbox.domain.wifi

/**
 * The platform seam that collects Wi-Fi pairing input (task 039: "No dedicated pairing layout is
 * supplied; use a native IntelliJ nonblocking flow") — implemented in `:intellij` by a native,
 * non-modal-friendly dialog, so application-layer orchestration stays testable with a fake and
 * carries no Swing/IntelliJ dependency (`kmp-ready`).
 */
interface WifiPairingInputPort {
    suspend fun collectPairingInput(): WifiPairingInput
}

/**
 * Raw, not-yet-validated user input for one pairing attempt. Validation (host/port/code syntax)
 * happens in the application layer, the same seam [dev.acme.adbtoolbox.domain.network.ProxyHost]/
 * [dev.acme.adbtoolbox.domain.network.ProxyPort] callers use, so this port stays a plain data
 * carrier with no validation rules of its own.
 */
sealed interface WifiPairingInput {
    data class Submitted(
        val pairingEndpointText: String,
        val pairingCodeText: String,
        val connectEndpointText: String,
    ) : WifiPairingInput {
        /**
         * Deliberately redacts [pairingCodeText] (task 039: "codes are never persisted or
         * logged") so an incidental `toString()` of the whole submission — a log line, an
         * exception message — can never leak the digits.
         */
        override fun toString(): String =
            "Submitted(pairingEndpointText=$pairingEndpointText, pairingCodeText=<redacted>, " +
                "connectEndpointText=$connectEndpointText)"
    }

    /** The user dismissed the dialog (Cancel, Escape, or window close) without submitting. */
    data object Cancelled : WifiPairingInput
}
