package io.github.dkej123.devicecockpit.domain.mirroring

/** A deterministic [MirroringOptionsRepository] test double, mirroring
 * [io.github.dkej123.devicecockpit.domain.nav.FakeNavigationPersistence]. */
class FakeMirroringOptionsRepository(
    initial: MirroringOptions = MirroringOptions.DEFAULT,
) : MirroringOptionsRepository {

    private var stored: MirroringOptions = initial

    private val _writes = mutableListOf<MirroringOptions>()
    val writes: List<MirroringOptions> get() = _writes

    override suspend fun readOptions(): MirroringOptions = stored

    override suspend fun writeOptions(options: MirroringOptions) {
        _writes += options
        stored = options
    }
}
