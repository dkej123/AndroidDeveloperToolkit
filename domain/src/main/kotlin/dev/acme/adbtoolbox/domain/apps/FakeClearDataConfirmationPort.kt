package dev.acme.adbtoolbox.domain.apps

/**
 * A deterministic [ClearDataConfirmationPort] test double: [response] is returned verbatim on
 * every call (default [ClearDataConfirmation.Confirmed], since most call sites only care about the
 * post-confirmation path), and every call's exact `(packageName, deviceLabel)` arguments are
 * recorded in [invocations] so a test can assert the dialog was asked about the exact target.
 */
class FakeClearDataConfirmationPort(
    private val response: ClearDataConfirmation = ClearDataConfirmation.Confirmed,
) : ClearDataConfirmationPort {

    data class Invocation(val packageName: String, val deviceLabel: String)

    private val _invocations = mutableListOf<Invocation>()
    val invocations: List<Invocation> get() = _invocations

    override suspend fun confirmClearData(packageName: String, deviceLabel: String): ClearDataConfirmation {
        _invocations += Invocation(packageName, deviceLabel)
        return response
    }
}
