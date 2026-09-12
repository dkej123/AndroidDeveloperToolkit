package dev.acme.adbtoolbox.intellij.devicefacts

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactValue
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton

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
    is DeviceFactValue.Uptime -> {
        val minutes = value.duration.inWholeMinutes
        "${minutes / 60}h ${minutes % 60}m"
    }
}

/**
 * The minimal, unstyled Device-view facts binding (task 015's scope: "a minimal functional
 * Device-view binding" — plain/functional, no styling; task 044 applies visuals). One [JBLabel]
 * per [DeviceFactId] (`design/README.md` §3's facts grid) plus the "Copy report" action
 * (`onCopyReport`). [update] is the only mutation entry point, driven by
 * [dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel.state] via
 * [DeviceFactsCoordinator].
 */
class DeviceFactsPanel(private val onCopyReport: () -> Unit) : JBPanel<DeviceFactsPanel>(BorderLayout()) {

    private val statusLabel = JBLabel("No device connected")

    private val factLabels: Map<DeviceFactId, JBLabel> =
        DeviceFactId.entries.associateWith { JBLabel("Loading…") }

    val copyReportButton = JButton("Copy report").apply {
        isEnabled = false
        addActionListener { onCopyReport() }
    }

    init {
        val factsGrid = JBPanel<Nothing>(GridLayout(DeviceFactId.entries.size, 2, 4, 2))
        DeviceFactId.entries.forEach { factId ->
            factsGrid.add(JBLabel(label(factId)))
            factsGrid.add(factLabels.getValue(factId))
        }

        add(statusLabel, BorderLayout.NORTH)
        add(factsGrid, BorderLayout.CENTER)
        add(copyReportButton, BorderLayout.SOUTH)
    }

    fun update(state: DeviceFactsViewState) {
        val snapshot = when (state) {
            is DeviceFactsViewState.Connected -> state.snapshot
            is DeviceFactsViewState.Partial -> state.snapshot
            else -> null
        }

        statusLabel.text = when (state) {
            DeviceFactsViewState.NoDevice -> "No device connected"
            DeviceFactsViewState.Loading -> "Loading…"
            is DeviceFactsViewState.RecoverableError -> "Error: ${state.message}"
            is DeviceFactsViewState.Connected -> snapshot?.serial.toString()
            is DeviceFactsViewState.Partial -> snapshot?.serial.toString()
        }

        DeviceFactId.entries.forEach { factId ->
            factLabels.getValue(factId).text = valueText(snapshot?.facts?.get(factId))
        }

        copyReportButton.isEnabled = snapshot != null
    }
}
