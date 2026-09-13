package dev.acme.adbtoolbox.application.wifi

/**
 * [WifiPairingViewModel]'s minimal presentation state. Deliberately carries no endpoint/code text —
 * every value the flow touches lives only as a local variable inside the coroutine that processes
 * one submission, so nothing here (and nothing this state is ever compared/logged against) can leak
 * a pairing code (task 039).
 */
data class WifiPairingViewState(val isBusy: Boolean = false)
