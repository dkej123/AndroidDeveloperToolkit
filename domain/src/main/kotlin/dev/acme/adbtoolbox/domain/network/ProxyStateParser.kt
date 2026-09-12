package dev.acme.adbtoolbox.domain.network

private val DISABLED_FORMS = setOf(":0", "null")

/**
 * Parses `settings get global http_proxy` stdout into a [ProxyReadState]. Normalizes CRLF/CR line
 * endings before matching disabled forms, then splits an active candidate on the *last* colon so
 * an IPv6 host round-trips without bracket syntax (matching [ProxyEndpoint.render]).
 */
fun parseProxyReadback(rawOutput: String): ProxyReadState {
    val normalized = rawOutput.replace("\r\n", "\n").replace('\r', '\n')
    val firstLine = normalized.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    if (firstLine.isEmpty()) return ProxyReadState.Disabled
    if (DISABLED_FORMS.any { it.equals(firstLine, ignoreCase = true) }) return ProxyReadState.Disabled

    val separatorIndex = firstLine.lastIndexOf(':')
    if (separatorIndex <= 0 || separatorIndex == firstLine.length - 1) return ProxyReadState.Unrecognized(rawOutput)

    val hostPart = firstLine.substring(0, separatorIndex)
    val portPart = firstLine.substring(separatorIndex + 1)

    val hostResult = ProxyHost.parse(hostPart)
    val portResult = ProxyPort.parse(portPart)
    return if (hostResult is ProxyHostResult.Valid && portResult is ProxyPortResult.Valid) {
        ProxyReadState.Active(ProxyEndpoint(hostResult.host, portResult.port))
    } else {
        ProxyReadState.Unrecognized(rawOutput)
    }
}
