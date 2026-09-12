package dev.acme.adbtoolbox.intellij.devicefacts

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactValue
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsSnapshot

private val serial = DeviceSerial.of("R58N90ABCDE")

/**
 * [DeviceFactsPanel] is the minimal, unstyled Device-view facts binding (task 015). Kept as a
 * [BasePlatformTestCase] like every other test in this module — see `AdbTransportSelectionTest`'s
 * class doc for why a plain JUnit 5 test class here is not discovered by `:intellij:test`'s runner.
 */
class DeviceFactsPanelTest : BasePlatformTestCase() {

    fun `test the Copy report button is disabled until a snapshot exists`() {
        var copyClicked = false
        val panel = DeviceFactsPanel(onCopyReport = { copyClicked = true })

        assertFalse(panel.copyReportButton.isEnabled)

        panel.update(DeviceFactsViewState.NoDevice)
        assertFalse(panel.copyReportButton.isEnabled)

        val snapshot = DeviceFactsSnapshot.loading(serial)
        panel.update(DeviceFactsViewState.Partial(snapshot))
        assertTrue(panel.copyReportButton.isEnabled)

        panel.copyReportButton.doClick()
        assertTrue(copyClicked)
    }

    fun `test rendering a Connected snapshot never fails even when one fact is Unavailable`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        val snapshot = DeviceFactsSnapshot(
            serial = serial,
            facts = mapOf(
                DeviceFactId.AndroidVersion to DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35)),
                DeviceFactId.Resolution to DeviceFactState.Unavailable("malformed"),
                DeviceFactId.Density to DeviceFactState.Available(DeviceFactValue.Density(420)),
                DeviceFactId.Battery to DeviceFactState.Available(DeviceFactValue.Battery(72, charging = true)),
                DeviceFactId.Abi to DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a")),
                DeviceFactId.Uptime to DeviceFactState.Loading,
            ),
        )

        panel.update(DeviceFactsViewState.Connected(snapshot))

        assertTrue(panel.copyReportButton.isEnabled)
    }
}
