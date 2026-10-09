package io.github.dkej123.devicecockpit.intellij.composition

import io.github.dkej123.devicecockpit.adapters.adb.selector.FallbackAdbTransport
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport

/**
 * ADR 0005's transport selection, made once per session at composition (this file), never per
 * call: when the Android plugin is present, [ddmlibTransport] is primary with [binaryTransport] as
 * the capability-gap fallback ([FallbackAdbTransport]'s "Unsupported only" rule — scrcpy, wireless
 * pairing, and any other ddmlib-incapable operation); when it is absent, [binaryTransport] is used
 * directly, since there is no ddmlib bridge to prefer. A pure function over already-constructed
 * transports (and a plain [Boolean] rather than reading platform plugin state itself) so it is
 * unit-testable without an IntelliJ Platform test fixture.
 */
fun selectAdbTransport(
    androidPluginPresent: Boolean,
    ddmlibTransport: AdbTransport,
    binaryTransport: AdbTransport,
): AdbTransport =
    if (androidPluginPresent) {
        FallbackAdbTransport(primary = ddmlibTransport, fallback = binaryTransport)
    } else {
        binaryTransport
    }
