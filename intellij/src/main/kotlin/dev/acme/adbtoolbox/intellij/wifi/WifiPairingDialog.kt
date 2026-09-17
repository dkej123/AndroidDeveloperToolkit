package dev.acme.adbtoolbox.intellij.wifi

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.util.Arrays
import javax.swing.JComponent
import javax.swing.SwingConstants

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

    private val pairingAddressField = JBTextField().also(::styleAddressField)
    private val pairingCodeField = JBPasswordField()
    private val connectAddressField = JBTextField().also(::styleAddressField)

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
        .addComponent(createPairingHeaderLabel())
        .addLabeledComponent(JBLabel("Pairing IP address & port"), pairingAddressField)
        .addLabeledComponent(JBLabel("Wi-Fi pairing code"), pairingCodeField)
        .addLabeledComponent(JBLabel("IP address & port (adb connect)"), connectAddressField)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = pairingAddressField
}

/** IP:port values are copy-paste targets per the design system's mono rule — pulled out so it is
 * unit-testable on a plain [JBTextField], matching [dev.acme.adbtoolbox.intellij.apps
 * .styleDestructiveButton]'s "extract the one styled bit" shape for an untested [DialogWrapper]. */
internal fun styleAddressField(field: JBTextField) {
    field.font = AdbToolboxTheme.Typography.mono
}

/** The dialog's own header, using the supplied `authorize` icon (`design/icons/actions/authorize*.svg`)
 * for this pairing/authorize entry point, since no bespoke pairing layout is supplied. */
internal fun createPairingHeaderLabel(): JBLabel = JBLabel(
    "Pair device over Wi-Fi",
    AdbToolboxIcons.Actions.authorize,
    SwingConstants.LEFT,
).apply {
    font = AdbToolboxTheme.Typography.sectionTitle
    foreground = AdbToolboxTheme.Colors.text
}
