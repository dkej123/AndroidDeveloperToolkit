package io.github.dkej123.devicecockpit.application.devicefacts

/** One-shot events [DeviceFactsViewModel] emits alongside state — never folded into [DeviceFactsViewState]. */
sealed interface DeviceFactsEffect {
    /** The report was written to the clipboard via [io.github.dkej123.devicecockpit.domain.devicefacts.ClipboardPort]. */
    data object ReportCopied : DeviceFactsEffect
}
