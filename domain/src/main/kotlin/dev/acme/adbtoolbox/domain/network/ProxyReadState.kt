package dev.acme.adbtoolbox.domain.network

/**
 * The device-truth result of reading `settings get global http_proxy` (task 030). [Disabled]
 * covers the several ways Android versions/OEMs represent "no proxy" (empty, `:0`, `null`).
 * [Active] is a syntactically valid endpoint. [Unrecognized] covers malformed, OEM-specific, or
 * permission-denied output that could not be parsed as either — never crashing the parser, and
 * never guessed at as Active/Disabled.
 */
sealed interface ProxyReadState {
    data object Disabled : ProxyReadState
    data class Active(val endpoint: ProxyEndpoint) : ProxyReadState
    data class Unrecognized(val raw: String) : ProxyReadState
}
