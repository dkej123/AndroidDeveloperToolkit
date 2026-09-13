package dev.acme.adbtoolbox.intellij.wifi

import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.wifi.WifiPairingInput
import dev.acme.adbtoolbox.domain.wifi.WifiPairingInputPort
import kotlinx.coroutines.withContext

/** The raw text a [WifiPairingDialog] produced, before task 039's domain-layer validation. */
internal data class WifiPairingDialogResult(
    val confirmed: Boolean,
    val pairingAddressText: String,
    val pairingCodeText: String,
    val connectAddressText: String,
) {
    /** Deliberately redacts [pairingCodeText] — see [WifiPairingInput.Submitted]'s own toString(). */
    override fun toString(): String =
        "WifiPairingDialogResult(confirmed=$confirmed, pairingAddressText=$pairingAddressText, " +
            "pairingCodeText=<redacted>, connectAddressText=$connectAddressText)"
}

internal fun wifiPairingInputForDialogResult(result: WifiPairingDialogResult): WifiPairingInput =
    if (result.confirmed) {
        WifiPairingInput.Submitted(result.pairingAddressText, result.pairingCodeText, result.connectAddressText)
    } else {
        WifiPairingInput.Cancelled
    }

/** IntelliJ adapter for task 039's pairing-input dialog: the only place [WifiPairingDialog] is constructed. */
internal class WifiPairingInputPresenter(
    private val project: Project,
    private val dispatchers: DispatcherProvider,
    private val showDialog: (Project) -> WifiPairingDialogResult = ::showWifiPairingDialog,
) : WifiPairingInputPort {

    override suspend fun collectPairingInput(): WifiPairingInput =
        withContext(dispatchers.main) { wifiPairingInputForDialogResult(showDialog(project)) }
}

private fun showWifiPairingDialog(project: Project): WifiPairingDialogResult {
    val dialog = WifiPairingDialog(project)
    val confirmed = dialog.showAndGet()
    val codeChars = dialog.readPairingCode()
    val result = WifiPairingDialogResult(
        confirmed = confirmed,
        pairingAddressText = dialog.pairingAddressText,
        pairingCodeText = String(codeChars),
        connectAddressText = dialog.connectAddressText,
    )
    java.util.Arrays.fill(codeChars, ' ')
    dialog.clearPairingCode()
    return result
}
