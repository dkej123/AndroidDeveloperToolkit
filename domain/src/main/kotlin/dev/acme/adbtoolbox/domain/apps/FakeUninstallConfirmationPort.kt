package dev.acme.adbtoolbox.domain.apps

class FakeUninstallConfirmationPort(
    private val response: UninstallConfirmation = UninstallConfirmation.Confirmed,
) : UninstallConfirmationPort {
    data class Invocation(val packageName: String, val deviceLabel: String)

    private val _invocations = mutableListOf<Invocation>()
    val invocations: List<Invocation> get() = _invocations

    override suspend fun confirmUninstall(packageName: String, deviceLabel: String): UninstallConfirmation {
        _invocations += Invocation(packageName, deviceLabel)
        return response
    }
}
