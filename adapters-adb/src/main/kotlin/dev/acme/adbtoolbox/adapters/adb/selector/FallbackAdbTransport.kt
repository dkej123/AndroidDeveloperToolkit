package dev.acme.adbtoolbox.adapters.adb.selector

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.process.ByteSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADR 0005's fallback matrix, implemented as a reusable [AdbTransport] that composes two others:
 * every call goes to [primary] first, and [fallback] is only ever consulted when [primary] reports
 * [AdbOutcome.Unsupported] — a capability gap [primary] detects *before* attempting any device call
 * (e.g. ddmlib has no wireless-pairing or scrcpy equivalent). Any other non-success outcome
 * ([AdbOutcome.TransportFailure], [AdbOutcome.TimedOut], [AdbOutcome.Cancelled]) is surfaced exactly
 * as [primary] produced it and is never retried: none of those outcomes prove a mutating command
 * did not already reach the device, so retrying on [fallback] could double-execute it.
 *
 * Whichever transport actually handles a call has its result returned unmodified — never re-wrapped
 * or summarized — so diagnostics, exit status, and which transport ran are preserved by
 * construction. Selecting *which* transport is `primary` (ddmlib vs. binary, per the Android plugin
 * being present) is a `:intellij` composition-root concern (task 007); this class only implements
 * the fallback-safety rule generically, over fakes as easily as real adapters.
 */
class FallbackAdbTransport(
    private val primary: AdbTransport,
    private val fallback: AdbTransport,
) : AdbTransport {

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val result = primary.executeText(request)
        return if (result.outcome is AdbOutcome.Unsupported) fallback.executeText(request) else result
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flow {
        var first = true
        var fellBack = false
        primary.executeStream(request).collect { event ->
            if (first) {
                first = false
                if (event is AdbStreamEvent.Completed && event.outcome is AdbOutcome.Unsupported) {
                    fellBack = true
                    return@collect
                }
            }
            emit(event)
        }
        if (fellBack) {
            fallback.executeStream(request).collect { emit(it) }
        }
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome {
        val outcome = primary.executeBinary(request, sink)
        return if (outcome is AdbOutcome.Unsupported) fallback.executeBinary(request, sink) else outcome
    }
}
