package dev.acme.adbtoolbox.intellij.clipboard

import com.intellij.openapi.ide.CopyPasteManager
import dev.acme.adbtoolbox.domain.devicefacts.ClipboardPort
import java.awt.datatransfer.StringSelection

/**
 * The `:intellij` [ClipboardPort] adapter (task 015): delegates to the platform's
 * [CopyPasteManager] rather than `java.awt.Toolkit`'s system clipboard directly, matching this
 * module's established precedent of implementing a `:domain` port with an IntelliJ Platform API
 * directly in `:intellij` (e.g.
 * [dev.acme.adbtoolbox.intellij.persistence.DeviceSelectionPersistenceAdapter] wrapping
 * `PersistentStateComponent`) rather than routing through `:adapters-jvm`, since the platform API
 * this needs is IntelliJ-only, not merely JVM-only (ADR 0001).
 */
class ClipboardPortAdapter : ClipboardPort {
    override fun writeText(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}
