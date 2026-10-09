package io.github.dkej123.devicecockpit.application.devicefacts

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbParseResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactId
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactState
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactValue
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactsCommands
import io.github.dkej123.devicecockpit.domain.devicefacts.DeviceFactsParsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Fetches every [DeviceFactId] for an explicit [DeviceSerial] through [transport] (ADR 0005: no
 * implicit "current device," every call targets a serial the caller supplies). Every fact is
 * requested independently through its own [io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand]
 * (`ADR 0001`'s builder/executor/parser separation), so [DeviceFactState.Unavailable] on one fact
 * can never affect another's result (task 015's acceptance criteria).
 *
 * [execute] returns a cold, bounded [Flow] emitting one `(DeviceFactId, DeviceFactState)` update
 * per fact as it settles, then completing once every fact has — letting a caller render facts
 * progressively rather than waiting for all six (`design/README.md`'s "loading state" facts
 * skeleton naturally resolves fact-by-fact). All six fetches run concurrently as children of the
 * flow's own [channelFlow] scope, so cancelling collection (e.g. the caller's `collectLatest`
 * moving to a different serial) tears every in-flight fetch down immediately — never leaves one
 * running for a device the user has since navigated away from (ADR 0005's stream-cancellation
 * rule, applied here to a bounded fan-out rather than a long-running stream).
 */
class LoadDeviceFactsUseCase(private val transport: AdbTransport) {

    fun execute(
        serial: DeviceSerial,
        facts: Collection<DeviceFactId> = DeviceFactId.entries,
    ): Flow<Pair<DeviceFactId, DeviceFactState>> = channelFlow {
        facts.forEach { factId ->
            launch {
                send(factId to fetchFact(serial, factId))
            }
        }
    }

    private suspend fun fetchFact(serial: DeviceSerial, factId: DeviceFactId): DeviceFactState {
        val request = AdbDeviceRequest(serial, AdbOperation.Shell(DeviceFactsCommands.commandFor(factId)))
        val result = transport.executeText(request)

        return when (val outcome = result.outcome) {
            is AdbOutcome.Completed -> parse(factId, result.stdout).let { parsed ->
                when (parsed) {
                    is AdbParseResult.Parsed -> DeviceFactState.Available(parsed.value)
                    is AdbParseResult.Malformed -> DeviceFactState.Unavailable(parsed.reason)
                }
            }
            AdbOutcome.TimedOut -> DeviceFactState.Unavailable("timed out")
            AdbOutcome.Cancelled -> DeviceFactState.Unavailable("cancelled")
            is AdbOutcome.TransportFailure -> DeviceFactState.Unavailable(outcome.reason)
            is AdbOutcome.Unsupported -> DeviceFactState.Unavailable(outcome.reason)
        }
    }

    private fun parse(factId: DeviceFactId, stdout: String): AdbParseResult<DeviceFactValue> = when (factId) {
        DeviceFactId.AndroidVersion -> DeviceFactsParsers.parseAndroidVersion(stdout)
        DeviceFactId.Resolution -> DeviceFactsParsers.parseResolution(stdout)
        DeviceFactId.Density -> DeviceFactsParsers.parseDensity(stdout)
        DeviceFactId.Battery -> DeviceFactsParsers.parseBattery(stdout)
        DeviceFactId.Abi -> DeviceFactsParsers.parseAbi(stdout)
        DeviceFactId.Uptime -> DeviceFactsParsers.parseUptime(stdout)
    }
}
