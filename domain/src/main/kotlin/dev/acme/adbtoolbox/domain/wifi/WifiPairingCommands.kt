package dev.acme.adbtoolbox.domain.wifi

import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import kotlin.time.Duration

/**
 * Builds the exact `adb pair <host:port> <code>` / `adb connect <host:port>` server-scoped requests
 * `design/IMPLEMENTATION.md` §4 specifies (ADR 0005: wireless pair/connect always uses the binary
 * transport — the ddmlib transport reports [dev.acme.adbtoolbox.domain.adb.AdbOutcome.Unsupported]
 * for any non-device-scoped request, which the fallback selector routes to binary automatically).
 * Arguments are literal argv, never remote-shell text, so no shell-escaping applies here — the only
 * way to reach either function is with already-validated [WifiEndpoint]/[PairingCode] values.
 */
object WifiPairingCommands {

    fun pair(endpoint: WifiEndpoint, code: PairingCode, timeout: Duration): AdbServerRequest =
        AdbServerRequest(arguments = listOf("pair", endpoint.render(), code.value), timeout = timeout)

    fun connect(endpoint: WifiEndpoint, timeout: Duration): AdbServerRequest =
        AdbServerRequest(arguments = listOf("connect", endpoint.render()), timeout = timeout)
}
