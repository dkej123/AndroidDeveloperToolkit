package dev.acme.adbtoolbox.e2e.tests

import dev.acme.adbtoolbox.e2e.infra.Adb
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.IdeProbes
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.doubles.shouldBeLessThan
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import kotlin.concurrent.thread

/**
 * What a user feels as "the IDE got slow": CPU burned by IDE threads while idle, and how long a
 * click waits for the UI thread under load. Each budget is generous on purpose — they catch
 * regressions like a spinning background thread or O(buffer) work on the EDT, not micro-tuning.
 * Measurements are appended to e2e/build/e2e-report/perf.txt for trend comparison.
 */
@Tag("perf")
class PerformanceE2ETest : E2eTest() {

    @Test
    fun `idle with a device connected the IDE does not burn CPU`() {
        studio.navigate(View.Device)
        Thread.sleep(5_000) // let startup queries settle

        val sample = IdeProbes.sampleCpu(20.0)
        record("idle Device view", sample)

        sample.percent("AWT-EventQueue") shouldBeLessThan 10.0
        sample.percent("DefaultDispatch") shouldBeLessThan 30.0
        sample.threads.maxOf { it.percentOfOneCore } shouldBeLessThan 40.0
    }

    @Test
    fun `a large Logcat buffer does not keep the UI thread busy`() {
        studio.navigate(View.Logcat)
        Adb.floodLogcat(20_000, "e2e-flood-idle")
        Thread.sleep(E2eConfig.deviceTimeout(5).toMillis()) // drain

        val sample = IdeProbes.sampleCpu(15.0)
        record("idle after 20k-line flood (Logcat view)", sample)

        sample.percent("AWT-EventQueue") shouldBeLessThan 15.0
    }

    @Test
    fun `UI stays responsive while Logcat streams a flood`() {
        studio.navigate(View.Logcat)
        val flood = thread(name = "e2e-flood") { Adb.floodLogcat(20_000, "e2e-flood-live") }

        val latency = IdeProbes.edtLatency(studio.robot, samples = 60, intervalMillis = 100)
        flood.join()
        record("EDT latency during 20k-line flood", latency)

        latency.p95Millis shouldBeLessThan 250.0
        latency.maxMillis shouldBeLessThan 1_000.0
    }

    @Test
    fun `typing a Logcat search over a full buffer keeps the UI responsive`() {
        studio.navigate(View.Logcat)
        Adb.floodLogcat(20_000, "e2e-flood-search")
        Thread.sleep(E2eConfig.deviceTimeout(5).toMillis())
        val search = studio.byName("Search log")

        var latency: IdeProbes.EdtLatency? = null
        val probe = thread(name = "e2e-edt-probe") { latency = IdeProbes.edtLatency(studio.robot, samples = 40, intervalMillis = 50) }
        studio.typeInto(search, "ActivityManager")
        probe.join()
        studio.setTextOf(search, "")
        record("EDT latency while typing a search over the buffer", latency!!)

        latency!!.maxMillis shouldBeLessThan 500.0
    }

    @Test
    fun `listing system packages finishes and leaves no busy threads`() {
        studio.navigate(View.Apps)
        val started = System.nanoTime()
        studio.click("Show system packages")
        awaitUntil(E2eConfig.deviceTimeout(60), Duration.ofMillis(500), "system packages") {
            studio.listModelItems(studio.component("//div[@class='AppsVirtualList']")).any { "com.android.settings" in it }
        }
        val listed = (System.nanoTime() - started) / 1e9
        Thread.sleep(E2eConfig.deviceTimeout(10).toMillis()) // label/debuggable enrichment

        val sample = IdeProbes.sampleCpu(10.0)
        studio.click("Show system packages")
        record("system packages listed in %.1fs, then".format(listed), sample)

        sample.percent("DefaultDispatch") shouldBeLessThan 30.0
    }

    private fun record(what: String, value: Any) {
        val line = when (value) {
            is IdeProbes.CpuSample -> "$what: total=%.0f%% edt=%.0f%% default=%.0f%% top=[%s]".format(
                value.totalPercent, value.percent("AWT-EventQueue"), value.percent("DefaultDispatch"), value.top())
            is IdeProbes.EdtLatency -> "$what: mean=%.0fms p95=%.0fms max=%.0fms (n=%d)".format(
                value.meanMillis, value.p95Millis, value.maxMillis, value.samples)
            else -> "$what: $value"
        }
        println("[perf] $line")
        File(E2eConfig.reportDir, "perf.txt").appendText("${java.time.Instant.now()} $line\n")
    }
}
