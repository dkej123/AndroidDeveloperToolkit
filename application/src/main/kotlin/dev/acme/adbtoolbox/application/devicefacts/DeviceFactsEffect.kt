package dev.acme.adbtoolbox.application.devicefacts

/** One-shot events [DeviceFactsViewModel] emits alongside state — never folded into [DeviceFactsViewState]. */
sealed interface DeviceFactsEffect {
    /** The report was written to the clipboard via [dev.acme.adbtoolbox.domain.devicefacts.ClipboardPort]. */
    data object ReportCopied : DeviceFactsEffect
}
