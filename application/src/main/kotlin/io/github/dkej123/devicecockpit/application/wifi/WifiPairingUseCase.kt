package io.github.dkej123.devicecockpit.application.wifi

import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.device.DeviceListRefresher
import io.github.dkej123.devicecockpit.domain.wifi.PairingCode
import io.github.dkej123.devicecockpit.domain.wifi.WifiEndpoint
import io.github.dkej123.devicecockpit.domain.wifi.WifiPairingCommands
import io.github.dkej123.devicecockpit.domain.wifi.WifiPairingStageResult
import io.github.dkej123.devicecockpit.domain.wifi.parseConnectStageResult
import io.github.dkej123.devicecockpit.domain.wifi.parsePairStageResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val DEFAULT_WIFI_PAIR_TIMEOUT: Duration = 15.seconds
val DEFAULT_WIFI_CONNECT_TIMEOUT: Duration = 15.seconds

/** Task 039's terminal pairing result — mirrors [WifiPairingStageResult]'s explicit cases per stage. */
sealed interface WifiPairingOutcome {
    data object Success : WifiPairingOutcome
    data class PairFailed(val reason: String) : WifiPairingOutcome
    data class ConnectFailed(val reason: String) : WifiPairingOutcome
    data object TimedOut : WifiPairingOutcome
    data object Cancelled : WifiPairingOutcome
}

/**
 * Task 039's pair-then-connect orchestration, run entirely against [transport] (always the binary
 * transport per ADR 0005 — this class never selects/falls back itself, it only issues
 * [io.github.dkej123.devicecockpit.domain.adb.AdbServerRequest]s that ddmlib reports
 * [io.github.dkej123.devicecockpit.domain.adb.AdbOutcome.Unsupported] for, which
 * [io.github.dkej123.devicecockpit.adapters.adb.selector.FallbackAdbTransport] already routes to binary).
 * [refresher] is only invoked after both stages succeed, so device discovery only re-runs for a
 * device that is now actually reachable — never on a partial pair-without-connect.
 */
class WifiPairingUseCase(
    private val transport: AdbTransport,
    private val refresher: DeviceListRefresher,
    private val pairTimeout: Duration = DEFAULT_WIFI_PAIR_TIMEOUT,
    private val connectTimeout: Duration = DEFAULT_WIFI_CONNECT_TIMEOUT,
) {
    suspend fun pairAndConnect(
        pairingEndpoint: WifiEndpoint,
        code: PairingCode,
        connectEndpoint: WifiEndpoint,
    ): WifiPairingOutcome {
        when (val pairResult = parsePairStageResult(transport.executeText(WifiPairingCommands.pair(pairingEndpoint, code, pairTimeout)))) {
            is WifiPairingStageResult.Failure -> return WifiPairingOutcome.PairFailed(pairResult.reason)
            WifiPairingStageResult.TimedOut -> return WifiPairingOutcome.TimedOut
            WifiPairingStageResult.Cancelled -> return WifiPairingOutcome.Cancelled
            WifiPairingStageResult.Success -> Unit
        }

        return when (val connectResult = parseConnectStageResult(transport.executeText(WifiPairingCommands.connect(connectEndpoint, connectTimeout)))) {
            is WifiPairingStageResult.Failure -> WifiPairingOutcome.ConnectFailed(connectResult.reason)
            WifiPairingStageResult.TimedOut -> WifiPairingOutcome.TimedOut
            WifiPairingStageResult.Cancelled -> WifiPairingOutcome.Cancelled
            WifiPairingStageResult.Success -> {
                refresher.refresh()
                WifiPairingOutcome.Success
            }
        }
    }
}
