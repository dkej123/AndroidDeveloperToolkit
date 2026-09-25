package dev.acme.adbtoolbox.intellij.dispatch

import com.intellij.openapi.application.EDT
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.diagnostics.NoOpDiagnosticsLog
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.diagnostics.TimingDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Production [DispatcherProvider] wiring (task 007, ADR 0004): [io]/[default] are the standard
 * kotlinx.coroutines pools (device/process/ADB work never runs on EDT), and [main] is the IntelliJ
 * Platform's own EDT-backed dispatcher so state marshaled through it is always safe to touch Swing
 * from — the only place in this plugin allowed to reference `Dispatchers.EDT`/IO/Default directly,
 * per ADR 0004's dispatcher-injection rule.
 *
 * [main] keeps every element of `Dispatchers.EDT` (e.g. modality) but routes dispatches through a
 * [TimingDispatcher], so plugin work that stalls the UI thread shows up in the diagnostics log.
 */
class IdeDispatcherProvider(log: DiagnosticsLog = NoOpDiagnosticsLog) : DispatcherProvider {
    override val default = Dispatchers.Default
    override val io = Dispatchers.IO
    override val main: CoroutineContext = timed(Dispatchers.EDT, log)

    private fun timed(edt: CoroutineContext, log: DiagnosticsLog): CoroutineContext {
        if (log === NoOpDiagnosticsLog) return edt
        val dispatcher = edt[ContinuationInterceptor] as? CoroutineDispatcher ?: return edt
        return edt + TimingDispatcher(dispatcher, log)
    }
}
