package dev.acme.adbtoolbox.intellij.diagnostics

import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

/**
 * Wraps the EDT dispatcher and times every block the plugin dispatches onto it. A block that holds
 * the EDT for [thresholdMs] or longer is a UI stall the user feels; it is logged as WARN with the
 * continuation it resumed (class and line of the suspended plugin code), which pinpoints the
 * responsible feature without a profiler. Dispatch semantics are fully delegated.
 */
class TimingDispatcher(
    private val delegate: CoroutineDispatcher,
    private val log: DiagnosticsLog,
    private val thresholdMs: Long = 50,
    private val nanoTime: () -> Long = System::nanoTime,
) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            val task = block.toString()
            val start = nanoTime()
            try {
                block.run()
            } finally {
                val ms = (nanoTime() - start) / 1_000_000
                if (ms >= thresholdMs) {
                    log.log(
                        DiagLevel.WARN,
                        DiagCategory.EDT,
                        "slow EDT task",
                        mapOf("ms" to ms, "coroutine" to context[CoroutineName]?.name, "task" to task.take(400)),
                    )
                }
            }
        }
    }

    override fun toString(): String = "Timing($delegate)"
}
