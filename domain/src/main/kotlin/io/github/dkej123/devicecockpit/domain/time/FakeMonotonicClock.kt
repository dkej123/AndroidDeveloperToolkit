package io.github.dkej123.devicecockpit.domain.time

import kotlin.time.Duration

/**
 * A deterministic [MonotonicClock] test double (mirrors [io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport]'s
 * scripted-rather-than-real shape): starts at [initial] and only ever advances when a test explicitly
 * calls [advanceBy] — never real wall-clock or scheduler time — so recording elapsed-time tests are
 * fully deterministic and instantaneous.
 */
class FakeMonotonicClock(initial: Duration = Duration.ZERO) : MonotonicClock {

    private var current = initial

    override fun markNow(): Duration = current

    fun advanceBy(duration: Duration) {
        current += duration
    }
}
