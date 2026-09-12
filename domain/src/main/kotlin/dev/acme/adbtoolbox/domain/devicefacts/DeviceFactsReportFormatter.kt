package dev.acme.adbtoolbox.domain.devicefacts

import kotlin.time.Duration

/**
 * Formats one [DeviceFactsSnapshot] as deterministic, clipboard-copyable plain text (task 015,
 * `design/README.md` §3's Device-section "Copy report" link): device identity (the exact serial)
 * followed by every fact in a fixed order, one `Label: value` line each. A fact that has not
 * settled or failed to resolve is rendered as its own line with a placeholder value — never
 * omitted — so the report's line count/order never depends on which facts happened to load.
 */
object DeviceFactsReportFormatter {

    fun format(snapshot: DeviceFactsSnapshot): String {
        val lines = mutableListOf("Device: ${snapshot.serial}")
        DeviceFactId.entries.forEach { factId ->
            lines += "${label(factId)}: ${valueText(snapshot.facts[factId])}"
        }
        return lines.joinToString("\n")
    }

    private fun label(factId: DeviceFactId): String = when (factId) {
        DeviceFactId.AndroidVersion -> "Android"
        DeviceFactId.Resolution -> "Resolution"
        DeviceFactId.Density -> "Density"
        DeviceFactId.Battery -> "Battery"
        DeviceFactId.Abi -> "ABI"
        DeviceFactId.Uptime -> "Uptime"
    }

    private fun valueText(state: DeviceFactState?): String = when (state) {
        null, DeviceFactState.Loading -> "Loading…"
        is DeviceFactState.Unavailable -> "Unavailable"
        is DeviceFactState.Available -> valueText(state.value)
    }

    private fun valueText(value: DeviceFactValue): String = when (value) {
        is DeviceFactValue.AndroidVersion -> "${value.release} · API ${value.sdk}"
        is DeviceFactValue.Resolution -> "${value.widthPx}x${value.heightPx}"
        is DeviceFactValue.Density -> "${value.dpi} dpi"
        is DeviceFactValue.Battery -> "${value.levelPercent}% · ${if (value.charging) "charging" else "not charging"}"
        is DeviceFactValue.Abi -> value.value
        is DeviceFactValue.Uptime -> formatUptime(value.duration)
    }

    private fun formatUptime(duration: Duration): String {
        val totalMinutes = duration.inWholeMinutes
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
