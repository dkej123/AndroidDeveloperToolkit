package dev.acme.adbtoolbox.intellij.diagnostics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.RecordingDiagnosticsLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class TimingDispatcherTest : BasePlatformTestCase() {

    private object Inline : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    fun `test a block holding the dispatcher past the threshold is logged as a slow EDT task`() {
        val log = RecordingDiagnosticsLog()
        var now = 0L
        val dispatcher = TimingDispatcher(Inline, log, thresholdMs = 50, nanoTime = { now })

        dispatcher.dispatch(CoroutineName("logcat-render")) { now += 80_000_000 }
        dispatcher.dispatch(EmptyCoroutineContext) { now += 10_000_000 }

        val entry = log.inCategory(DiagCategory.EDT).single()
        assertEquals(DiagLevel.WARN, entry.level)
        assertEquals(80L, entry.fields["ms"])
        assertEquals("logcat-render", entry.fields["coroutine"])
    }
}
