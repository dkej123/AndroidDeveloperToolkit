package dev.acme.adbtoolbox.domain.devicefacts

/**
 * Isolates system clipboard access behind a `:domain` port (task 015's scope) so
 * `:application`'s [dev.acme.adbtoolbox.domain.devicefacts] use case/ViewModel stays KMP-ready and
 * platform-free (ADR 0002) — the real adapter (`:intellij`, `com.intellij.openapi.ide.CopyPasteManager`)
 * lives behind this port rather than being called directly from the ViewModel.
 */
interface ClipboardPort {
    fun writeText(text: String)
}
