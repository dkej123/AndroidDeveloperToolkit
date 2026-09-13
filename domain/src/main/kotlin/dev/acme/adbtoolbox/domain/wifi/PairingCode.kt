package dev.acme.adbtoolbox.domain.wifi

/**
 * A validated 6-digit Wi-Fi pairing code, matching Android's Wireless debugging pairing dialog.
 * The only way to obtain an instance is [PairingCode.parse]. [toString] is deliberately redacted
 * (task 039: "codes are never persisted or logged") so an incidental `toString()` call — a log
 * line, an exception message, a debugger watch — can never leak the digits; call [value] explicitly
 * at the one point a command factory needs the real text.
 */
@JvmInline
value class PairingCode private constructor(val value: String) {

    override fun toString(): String = "PairingCode(******)"

    companion object {
        private val PATTERN = Regex("^\\d{6}$")

        fun parse(raw: String): PairingCodeResult {
            val trimmed = raw.trim()
            return if (PATTERN.matches(trimmed)) {
                PairingCodeResult.Valid(PairingCode(trimmed))
            } else {
                PairingCodeResult.Invalid("Pairing code must be exactly 6 digits")
            }
        }
    }
}

sealed interface PairingCodeResult {
    data class Valid(val code: PairingCode) : PairingCodeResult
    data class Invalid(val reason: String) : PairingCodeResult
}
