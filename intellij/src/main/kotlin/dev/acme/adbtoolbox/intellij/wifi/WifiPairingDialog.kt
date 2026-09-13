package dev.acme.adbtoolbox.intellij.wifi

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.util.Arrays
import javax.swing.JComponent

/**
 * Task 039's native, no-bespoke-layout pairing input dialog (`design/README.md` §1's "Pair device
 * over Wi-Fi…" entry point; no dedicated pairing layout is supplied, so this uses IntelliJ's own
 * [DialogWrapper]/[FormBuilder] the same way [dev.acme.adbtoolbox.intellij.settings
 * .AdbToolboxSettingsConfigurable] builds its native settings form). The pairing code field is a
 * [JBPasswordField] — masked on screen — and [clearPairingCode] overwrites its backing char array
 * once the caller has read it, so the digits do not linger in a live Swing component any longer
 * than the single call that needs them (task 039: "codes are ... cleared on completion/disposal").
 */
internal class WifiPairingDialog(project: Project) : DialogWrapper(project, false) {

    private val pairingAddressField = JBTextField()
    private val pairingCodeField = JBPasswordField()
    private val connectAddressField = JBTextField()

    val pairingAddressText: String get() = pairingAddressField.text.orEmpty()
    val connectAddressText: String get() = connectAddressField.text.orEmpty()

    init {
        title = "Pair device over Wi-Fi"
        init()
    }

    /** Reads the code once; callers must not retain the returned array any longer than needed. */
    fun readPairingCode(): CharArray = pairingCodeField.password

    /** Overwrites the field's own backing characters — call once the code has been read and used. */
    fun clearPairingCode() {
        Arrays.fill(pairingCodeField.password, ' ')
        pairingCodeField.text = ""
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Pairing IP address & port"), pairingAddressField)
        .addLabeledComponent(JBLabel("Wi-Fi pairing code"), pairingCodeField)
        .addLabeledComponent(JBLabel("IP address & port (adb connect)"), connectAddressField)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = pairingAddressField
}
