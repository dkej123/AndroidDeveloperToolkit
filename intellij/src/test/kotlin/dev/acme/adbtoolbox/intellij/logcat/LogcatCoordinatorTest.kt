package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.logcat.LogcatControlsController
import dev.acme.adbtoolbox.application.logcat.LogcatControlsState
import dev.acme.adbtoolbox.application.logcat.LogcatFilterUpdate
import dev.acme.adbtoolbox.application.logcat.LogcatPackagePidTracker
import dev.acme.adbtoolbox.application.logcat.LogcatPidResolver
import dev.acme.adbtoolbox.application.logcat.LogcatSessionManager
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.FakeLogcatControlsPersistence
import dev.acme.adbtoolbox.domain.logcat.LogMessage
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogTag
import dev.acme.adbtoolbox.domain.logcat.LogTimestamp
import dev.acme.adbtoolbox.domain.logcat.LogcatEntry
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.domain.logcat.LogcatRecord
import dev.acme.adbtoolbox.domain.logcat.ProcessId
import dev.acme.adbtoolbox.domain.logcat.SequencedLogcatEntry
import dev.acme.adbtoolbox.domain.logcat.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

private val SERIAL = DeviceSerial.of("AAAA111")
private val TIMESTAMP = LogTimestamp(9, 10, 12, 34, 56, 789)

private fun sequencedRecord(sequence: Long, message: String, severity: LogSeverity = LogSeverity.INFO) =
    SequencedLogcatEntry(
        sequence = sequence,
        entry = LogcatEntry.Record(
            LogcatRecord(TIMESTAMP, ProcessId(1000), ThreadId(1000), severity, LogTag("Sample"), LogMessage(message)),
            continuationLines = emptyList(),
        ),
        sizeBytes = message.length,
    )

/**
 * Connects task 037's [LogcatControlsController] to [LogcatPanel]/the task 036 renderer, mirroring
 * [dev.acme.adbtoolbox.intellij.network.NetworkCoordinatorTest]'s established shape.
 */
class LogcatCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: collector renders are queued on the EDT behind the test body, so a
        // direct render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private class Fixture(
        val scope: CoroutineScope,
        val selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
        val selectedPackageState: MutableStateFlow<SelectedPackageState>,
        val controller: LogcatControlsController,
        val aggregator: DeviceContextAggregator,
        val dispatchers: DispatcherProvider,
    )

    private fun fixture(
        initialState: SelectedDeviceState = SelectedDeviceState.None,
    ): Fixture {
        val dispatchers: DispatcherProvider = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow(initialState)
        val selectedPackageState = MutableStateFlow<SelectedPackageState>(SelectedPackageState.None)
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        val sessionManager = LogcatSessionManager(scope, dispatchers, selectedDeviceState, transport)
        val pidTracker = LogcatPackagePidTracker(scope, dispatchers, selectedPackageState, LogcatPidResolver(transport))
        val controller = LogcatControlsController(
            scope, dispatchers, selectedDeviceState, selectedPackageState, sessionManager, pidTracker,
            FakeLogcatControlsPersistence(),
        )
        return Fixture(
            scope, selectedDeviceState, selectedPackageState, controller,
            DeviceContextAggregator(scope, selectedDeviceState), dispatchers,
        )
    }

    /** Runs a submitted EDT task immediately/synchronously, so tests can assert on the virtualized
     * list's model without pumping the real IDE event queue. */
    private val immediateScheduler = LogcatEdtScheduler { task ->
        task()
        LogcatScheduledTask {}
    }

    private fun coordinator(f: Fixture): LogcatCoordinator {
        val virtualList = LogcatVirtualList(LogcatVirtualListModel { true })
        return LogcatCoordinator(
            controller = f.controller,
            aggregator = f.aggregator,
            scope = f.scope,
            dispatchers = f.dispatchers,
            virtualList = virtualList,
            edtBatcher = LogcatEdtBatcher(virtualList.virtualModel, immediateScheduler),
        )
    }

    fun `test the coordinator wires up against a real controller without a construction-time crash`() {
        val f = fixture()

        val coordinator = coordinator(f)

        assertNotNull(coordinator.panel)
        coordinator.dispose()
    }

    fun `test typing in the search field issues a SetQuery intent to the controller`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.searchFieldForTest.text = "timeout"

        assertEquals("timeout", f.controller.state.value.query)
        coordinator.dispose()
    }

    fun `test clicking Pause toggles the controller's pause state`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.pauseButtonForTest.doClick()

        assertTrue(f.controller.state.value.pauseState is LogcatPauseState.Paused)
        coordinator.dispose()
    }

    fun `test clicking a level chip issues a SetMinSeverity intent`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.levelButtonsForTest.getValue(LogSeverity.ERROR).doClick()

        assertEquals(LogSeverity.ERROR, f.controller.state.value.minSeverity)
        coordinator.dispose()
    }

    fun `test clicking the package filter chip toggles it off`() {
        val f = fixture()
        val coordinator = coordinator(f)
        assertTrue(f.controller.state.value.packageFilterOn)

        coordinator.panel.packageFilterChipForTest.doClick()

        assertFalse(f.controller.state.value.packageFilterOn)
        coordinator.dispose()
    }

    fun `test clicking Clear issues a ClearLocal intent`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.panel.clearButtonForTest.doClick()

        assertTrue(f.controller.state.value.cleared)
        coordinator.dispose()
    }

    fun `test render reflects a constructed view state onto the panel and presentation directly`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.render(LogcatControlsState(query = "timeout", wrap = true, visibleCount = 3, totalRetainedCount = 5))

        assertEquals("timeout", coordinator.panel.searchFieldForTest.text)
        assertTrue(coordinator.virtualList.presentation.wrapLines)
        assertEquals("3 of 5 lines", coordinator.panel.footerLabelForTest.text)
        coordinator.dispose()
    }

    fun `test the empty state shows when there is no device`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.render(LogcatControlsState(serial = null))

        assertTrue(coordinator.panel.emptyStateLabelForTest.isVisible)
        assertEquals("No device connected", coordinator.panel.emptyStateLabelForTest.text)
        coordinator.dispose()
    }

    fun `test a reset filter update populates the virtualized list with matched spans`() {
        val f = fixture()
        val coordinator = coordinator(f)
        val entries = listOf(sequencedRecord(1, "a timeout occurred"), sequencedRecord(2, "all good"))

        coordinator.applyFilterUpdate(LogcatFilterUpdate.Reset(entries), query = "timeout")

        assertEquals(2, coordinator.virtualList.virtualModel.size)
        assertEquals(listOf(LogcatMatchSpan(2, 9)), coordinator.virtualList.virtualModel.getElementAt(0).matchSpans)
        assertEquals(emptyList<LogcatMatchSpan>(), coordinator.virtualList.virtualModel.getElementAt(1).matchSpans)
        coordinator.dispose()
    }

    fun `test a delta filter update appends rows without a full reset`() {
        val f = fixture()
        val coordinator = coordinator(f)
        coordinator.applyFilterUpdate(LogcatFilterUpdate.Reset(listOf(sequencedRecord(1, "first"))), query = "")

        coordinator.applyFilterUpdate(LogcatFilterUpdate.Delta(listOf(sequencedRecord(2, "second")), null), query = "")

        assertEquals(2, coordinator.virtualList.virtualModel.size)
        coordinator.dispose()
    }

    fun `test the badge contributor reflects an error session on the selected serial`() {
        // The fixture's fake logcat stream ends immediately, so the session for the selected
        // serial settles in Error; the contributor must then report Attention for that serial only.
        val f = fixture(initialState = SelectedDeviceState.Online(Device(SERIAL, DeviceConnectionState.Online)))
        val coordinator = coordinator(f)
        val contributor = LogcatBadgeContributor(f.controller)

        val deadline = System.currentTimeMillis() + 5_000
        while (f.controller.state.value.sessionState !is dev.acme.adbtoolbox.domain.logcat.LogcatSessionState.Error &&
            System.currentTimeMillis() < deadline
        ) {
            // The controller marshals onto the EDT (dispatchers.main); let it run while waiting.
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }

        assertEquals(dev.acme.adbtoolbox.domain.nav.NavigationBadge.Attention, contributor.badgeFor(SERIAL))
        assertEquals(dev.acme.adbtoolbox.domain.nav.NavigationBadge.None, contributor.badgeFor(DeviceSerial.of("OTHER001")))
        coordinator.dispose()
    }

    fun `test the Space key on the virtualized list toggles pause`() {
        val f = fixture()
        val coordinator = coordinator(f)

        val action = coordinator.virtualList.actionMap.get("logcat.togglePause")
        assertNotNull(action)
        action.actionPerformed(java.awt.event.ActionEvent(coordinator.virtualList, java.awt.event.ActionEvent.ACTION_PERFORMED, ""))

        assertTrue(f.controller.state.value.pauseState is LogcatPauseState.Paused)
        coordinator.dispose()
    }

    fun `test the End key on the virtualized list jumps to latest`() {
        val f = fixture()
        val coordinator = coordinator(f)
        f.controller.handle(dev.acme.adbtoolbox.application.logcat.LogcatControlsIntent.TogglePause)

        val action = coordinator.virtualList.actionMap.get("logcat.jumpToLatest")
        assertNotNull(action)
        action.actionPerformed(java.awt.event.ActionEvent(coordinator.virtualList, java.awt.event.ActionEvent.ACTION_PERFORMED, ""))

        assertEquals(LogcatPauseState.Resumed, f.controller.state.value.pauseState)
        coordinator.dispose()
    }

    fun `test escape in the search field clears the query without a global shortcut`() {
        val f = fixture()
        val coordinator = coordinator(f)
        coordinator.panel.searchFieldForTest.text = "timeout"

        val escape = java.awt.event.KeyEvent(
            coordinator.panel.searchFieldForTest, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
            0, java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED,
        )
        coordinator.panel.searchFieldForTest.keyListeners.forEach { it.keyPressed(escape) }

        assertEquals("", coordinator.panel.searchFieldForTest.text)
        coordinator.dispose()
    }

    fun `test disposing cancels the scope`() {
        val f = fixture()
        val coordinator = coordinator(f)

        coordinator.dispose()

        assertFalse(f.scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
    }
}
