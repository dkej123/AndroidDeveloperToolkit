package dev.acme.adbtoolbox.domain.nav

import kotlinx.coroutines.delay

/**
 * A deterministic [NavigationPersistence] test double, mirroring
 * [dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence]. [readDelayMillis]/
 * [writeDelayMillis] let tests exercise ordering under a virtual-time test dispatcher;
 * [readFailure], when set, makes [readLastView] throw once (then clears) to exercise the
 * recoverable-error path.
 */
class FakeNavigationPersistence(
    initial: ViewId? = null,
    private val readDelayMillis: Long = 0,
    private val writeDelayMillis: Long = 0,
) : NavigationPersistence {

    private var stored: ViewId? = initial
    var readFailure: Throwable? = null

    private val _writes = mutableListOf<ViewId>()
    val writes: List<ViewId> get() = _writes

    override suspend fun readLastView(): ViewId? {
        if (readDelayMillis > 0) delay(readDelayMillis)
        readFailure?.let {
            readFailure = null
            throw it
        }
        return stored
    }

    override suspend fun writeLastView(viewId: ViewId) {
        _writes += viewId
        if (writeDelayMillis > 0) delay(writeDelayMillis)
        stored = viewId
    }
}
