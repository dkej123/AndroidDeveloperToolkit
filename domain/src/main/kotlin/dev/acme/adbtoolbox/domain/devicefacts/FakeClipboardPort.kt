package dev.acme.adbtoolbox.domain.devicefacts

/** A deterministic [ClipboardPort] test double: records every [writeText] call instead of touching the real system clipboard. */
class FakeClipboardPort : ClipboardPort {
    private val _writes = mutableListOf<String>()
    val writes: List<String> get() = _writes

    override fun writeText(text: String) {
        _writes += text
    }
}
