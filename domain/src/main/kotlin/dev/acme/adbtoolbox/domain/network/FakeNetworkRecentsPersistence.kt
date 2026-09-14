package dev.acme.adbtoolbox.domain.network

/**
 * A deterministic [NetworkRecentsPersistence] test double, mirroring
 * [dev.acme.adbtoolbox.domain.apps.FakeSelectedPackagePersistence]. [readFailure], when set, makes
 * [readRecents] throw once (then clears) to exercise the recoverable-error path.
 */
class FakeNetworkRecentsPersistence(
    initial: List<ProxyEndpoint> = emptyList(),
) : NetworkRecentsPersistence {

    private var stored: List<ProxyEndpoint> = initial
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    private val _writes = mutableListOf<List<ProxyEndpoint>>()
    val writes: List<List<ProxyEndpoint>> get() = _writes

    override suspend fun readRecents(): List<ProxyEndpoint> {
        readFailure?.let {
            readFailure = null
            throw it
        }
        return stored
    }

    override suspend fun writeRecents(recents: List<ProxyEndpoint>) {
        writeFailure?.let {
            writeFailure = null
            throw it
        }
        _writes += recents
        stored = recents
    }
}
