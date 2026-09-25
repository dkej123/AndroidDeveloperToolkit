package dev.acme.adbtoolbox.adapters.jvm.diagnostics

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ThreadSampleAggregatorTest {

    private fun frame(name: String) = StackTraceElement("com.example.Cls", name, "Cls.kt", 1)

    @Test
    fun `counts top-of-stack frames as self time and every distinct frame as inclusive time`() {
        val aggregator = ThreadSampleAggregator(edtThreadPrefix = "AWT-EventQueue")

        repeat(3) {
            aggregator.add("AWT-EventQueue-0", Thread.State.RUNNABLE, arrayOf(frame("paint"), frame("dispatch"), frame("run")))
        }
        aggregator.add("AWT-EventQueue-0", Thread.State.RUNNABLE, arrayOf(frame("layout"), frame("dispatch"), frame("run")))
        aggregator.add("worker-1", Thread.State.RUNNABLE, arrayOf(frame("parse"), frame("run")))
        aggregator.sampleCompleted()

        val edt = aggregator.edtReport()
        edt.samples shouldBe 4
        edt.selfTop.first() shouldBe ("com.example.Cls.paint(Cls.kt:1)" to 3)
        edt.inclusiveTop.first().second shouldBe 4

        val report = aggregator.render(durationMs = 1000, intervalMs = 200)
        report shouldContain "samples: 1"
        report shouldContain "EDT (AWT-EventQueue"
        report shouldContain "com.example.Cls.paint(Cls.kt:1)"
        report shouldContain "com.example.Cls.parse(Cls.kt:1)"
    }

    @Test
    fun `waiting and parked threads are excluded from cpu hotspots`() {
        val aggregator = ThreadSampleAggregator(edtThreadPrefix = "AWT-EventQueue")

        aggregator.add("idle", Thread.State.WAITING, arrayOf(frame("park")))
        aggregator.add("busy", Thread.State.RUNNABLE, arrayOf(frame("spin")))

        aggregator.allReport().selfTop.map { it.first } shouldBe listOf("com.example.Cls.spin(Cls.kt:1)")
    }
}

class ThreadSamplingProfilerTest {
    @Test
    fun `a short recording samples live threads and renders both tables`() {
        val report = ThreadSamplingProfiler(intervalMs = 20, durationMs = 120).run()

        report shouldContain "ADB Toolbox performance recording"
        report shouldContain "All RUNNABLE threads"
    }

    @Test
    fun `cancellation stops the recording early`() {
        var polls = 0
        val report = ThreadSamplingProfiler(intervalMs = 10, durationMs = 60_000).run(isCancelled = { polls++ > 2 })

        report shouldContain "samples: 3"
    }
}

class JvmDiagnosticsTest {
    @Test
    fun `stats and thread dump describe the running JVM`() {
        JvmDiagnostics.stats().keys shouldContainAll listOf("heapUsedMb", "threads", "gcCount")
        JvmDiagnostics.threadDump() shouldContain Thread.currentThread().name
    }
}
