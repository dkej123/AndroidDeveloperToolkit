package dev.acme.adbtoolbox.domain.logcat

/**
 * A deterministic [LogcatControlsPersistence] test double, mirroring
 * [dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence]. [readFailure]/[writeFailure],
 * when set, make the next [read]/[write] throw once (then clear) to exercise the recoverable-error
 * path.
 */
class FakeLogcatControlsPersistence(
    initial: LogcatPersistedControls = LogcatPersistedControls(),
) : LogcatControlsPersistence {

    private var stored: LogcatPersistedControls = initial
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    private val _writes = mutableListOf<LogcatPersistedControls>()
    val writes: List<LogcatPersistedControls> get() = _writes

    override suspend fun read(): LogcatPersistedControls {
        readFailure?.let {
            readFailure = null
            throw it
        }
        return stored
    }

    override suspend fun write(controls: LogcatPersistedControls) {
        writeFailure?.let {
            writeFailure = null
            throw it
        }
        _writes += controls
        stored = controls
    }
}
