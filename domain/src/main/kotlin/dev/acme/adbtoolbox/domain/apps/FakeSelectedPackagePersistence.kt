package dev.acme.adbtoolbox.domain.apps

/**
 * A deterministic [SelectedPackagePersistence] test double, mirroring
 * [dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence]. [readFailure], when set, makes
 * [readSelectedPackage] throw once (then clears) to exercise the recoverable-error path.
 */
class FakeSelectedPackagePersistence(
    initial: SelectedPackage? = null,
) : SelectedPackagePersistence {

    private var stored: SelectedPackage? = initial
    var readFailure: Throwable? = null

    private val _writes = mutableListOf<SelectedPackage?>()
    val writes: List<SelectedPackage?> get() = _writes

    override suspend fun readSelectedPackage(): SelectedPackage? {
        readFailure?.let {
            readFailure = null
            throw it
        }
        return stored
    }

    override suspend fun writeSelectedPackage(selection: SelectedPackage?) {
        _writes += selection
        stored = selection
    }
}
