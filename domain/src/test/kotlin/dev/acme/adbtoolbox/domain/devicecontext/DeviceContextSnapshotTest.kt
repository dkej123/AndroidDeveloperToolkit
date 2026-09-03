package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [aggregateDeviceContext] is the whole aggregation mechanism (task 014): a pure function combining
 * whatever contributors are *currently* registered into one immutable snapshot for one serial. There
 * is no shared mutable map contributors write into — each fake contributor below owns and computes
 * its own state, and the function only reads it at aggregation time.
 */
class DeviceContextSnapshotTest {

    private val serialA = DeviceSerial.of("AAAA111")
    private val serialB = DeviceSerial.of("BBBB222")

    private class FakeBadgeContributor(
        override val viewId: ViewId,
        var badgesBySerial: Map<DeviceSerial?, NavigationBadge> = emptyMap(),
    ) : BadgeContributor {
        override fun badgeFor(serial: DeviceSerial?): NavigationBadge = badgesBySerial[serial] ?: NavigationBadge.None
    }

    private class FakeRunningProcessContributor(
        var processesBySerial: Map<DeviceSerial?, List<RunningProcessInfo>> = emptyMap(),
    ) : RunningProcessContributor {
        override fun runningProcessesFor(serial: DeviceSerial?): List<RunningProcessInfo> =
            processesBySerial[serial].orEmpty()
    }

    private class FakeOverrideSummaryContributor(
        var overridesBySerial: Map<DeviceSerial?, List<OverrideSummary>> = emptyMap(),
    ) : OverrideSummaryContributor {
        override fun overridesFor(serial: DeviceSerial?): List<OverrideSummary> = overridesBySerial[serial].orEmpty()
    }

    @Test
    fun `with no contributors registered, the snapshot for any serial is empty`() {
        val snapshot = aggregateDeviceContext(serialA, emptyList(), emptyList(), emptyList())

        snapshot shouldBe DeviceContextSnapshot(serialA, emptyMap(), emptyList(), emptyList())
    }

    @Test
    fun `multiple contributors of each kind combine into one snapshot`() {
        val logcatBadge = FakeBadgeContributor(ViewId.Logcat, mapOf(serialA to NavigationBadge.Attention))
        val displayBadge = FakeBadgeContributor(ViewId.Display, mapOf(serialA to NavigationBadge.Count(2)))
        val scrcpy = FakeRunningProcessContributor(mapOf(serialA to listOf(RunningProcessInfo("scrcpy", "Mirroring"))))
        val recording =
            FakeRunningProcessContributor(mapOf(serialA to listOf(RunningProcessInfo("screen-record", "REC 00:42"))))
        val display = FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("font-scale", "1.3x"))))
        val network = FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("proxy", "10.0.0.1:8080"))))

        val snapshot = aggregateDeviceContext(
            serialA,
            listOf(logcatBadge, displayBadge),
            listOf(scrcpy, recording),
            listOf(display, network),
        )

        snapshot shouldBe DeviceContextSnapshot(
            serial = serialA,
            badges = mapOf(ViewId.Logcat to NavigationBadge.Attention, ViewId.Display to NavigationBadge.Count(2)),
            runningProcesses = listOf(
                RunningProcessInfo("scrcpy", "Mirroring"),
                RunningProcessInfo("screen-record", "REC 00:42"),
            ),
            overrides = listOf(OverrideSummary("font-scale", "1.3x"), OverrideSummary("proxy", "10.0.0.1:8080")),
        )
    }

    @Test
    fun `a contributor reporting NavigationBadge None is omitted from the badge map`() {
        val badge = FakeBadgeContributor(ViewId.Logcat, mapOf(serialA to NavigationBadge.None))

        val snapshot = aggregateDeviceContext(serialA, listOf(badge), emptyList(), emptyList())

        snapshot.badges shouldBe emptyMap()
    }

    @Test
    fun `switching the requested serial never mixes another serial's contributor data`() {
        val processes = FakeRunningProcessContributor(
            mapOf(
                serialA to listOf(RunningProcessInfo("scrcpy", "Mirroring A")),
                serialB to listOf(RunningProcessInfo("scrcpy", "Mirroring B")),
            ),
        )

        val snapshotA = aggregateDeviceContext(serialA, emptyList(), listOf(processes), emptyList())
        val snapshotB = aggregateDeviceContext(serialB, emptyList(), listOf(processes), emptyList())

        snapshotA.runningProcesses shouldBe listOf(RunningProcessInfo("scrcpy", "Mirroring A"))
        snapshotB.runningProcesses shouldBe listOf(RunningProcessInfo("scrcpy", "Mirroring B"))
        snapshotA.serial shouldBe serialA
        snapshotB.serial shouldBe serialB
    }

    @Test
    fun `mutating one contributor's own internal state never affects another contributor's output or a prior snapshot`() {
        val overridesA = FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("font-scale", "1.3x"))))
        val overridesB = FakeOverrideSummaryContributor(mapOf(serialA to listOf(OverrideSummary("proxy", "10.0.0.1:8080"))))

        val before = aggregateDeviceContext(serialA, emptyList(), emptyList(), listOf(overridesA, overridesB))

        overridesA.overridesBySerial = mapOf(serialA to listOf(OverrideSummary("font-scale", "2.0x")))

        val after = aggregateDeviceContext(serialA, emptyList(), emptyList(), listOf(overridesA, overridesB))

        before.overrides shouldBe listOf(OverrideSummary("font-scale", "1.3x"), OverrideSummary("proxy", "10.0.0.1:8080"))
        after.overrides shouldBe listOf(OverrideSummary("font-scale", "2.0x"), OverrideSummary("proxy", "10.0.0.1:8080"))
    }
}
