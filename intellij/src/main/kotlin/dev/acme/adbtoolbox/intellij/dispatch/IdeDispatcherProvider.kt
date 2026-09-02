package dev.acme.adbtoolbox.intellij.dispatch

import com.intellij.openapi.application.EDT
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.Dispatchers

/**
 * Production [DispatcherProvider] wiring (task 007, ADR 0004): [io]/[default] are the standard
 * kotlinx.coroutines pools (device/process/ADB work never runs on EDT), and [main] is the IntelliJ
 * Platform's own EDT-backed dispatcher so state marshaled through it is always safe to touch Swing
 * from — the only place in this plugin allowed to reference `Dispatchers.EDT`/IO/Default directly,
 * per ADR 0004's dispatcher-injection rule.
 */
class IdeDispatcherProvider : DispatcherProvider {
    override val default = Dispatchers.Default
    override val io = Dispatchers.IO
    override val main = Dispatchers.EDT
}
