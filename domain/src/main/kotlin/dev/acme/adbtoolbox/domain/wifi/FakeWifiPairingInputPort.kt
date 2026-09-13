package dev.acme.adbtoolbox.domain.wifi

/** A scripted [WifiPairingInputPort] test double: replays [responses] in order, repeating the last. */
class FakeWifiPairingInputPort(
    private val responses: List<WifiPairingInput> = listOf(WifiPairingInput.Cancelled),
) : WifiPairingInputPort {

    var invocationCount: Int = 0
        private set
    private var index = 0

    override suspend fun collectPairingInput(): WifiPairingInput {
        invocationCount++
        val response = responses.getOrElse(index) { responses.last() }
        if (index < responses.size - 1) index++
        return response
    }
}
