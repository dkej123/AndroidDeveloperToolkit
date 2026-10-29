package io.github.dkej123.devicecockpit.intellij.persistence

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.*
import io.github.dkej123.devicecockpit.application.deeplinks.DeviceDeepLinkMemory
import io.github.dkej123.devicecockpit.application.deeplinks.RecentDeviceLink

/** Global device-link history, as specified in design/DEEP_LINK_DIALOG.md. */
@Service(Service.Level.APP)
@State(name = "AdbToolboxDeepLinkHistory", storages = [Storage("adbToolboxDeepLinks.xml")])
class DeviceDeepLinkHistory : PersistentStateComponent<DeviceDeepLinkHistory.HistoryState>, DeviceDeepLinkMemory {
    class Entry {
        var uri: String = ""
        var pkg: String = ""
        var lastRun: Long = 0
        var handledBy: String = ""
    }
    class HistoryState { var entries: MutableList<Entry> = mutableListOf() }
    private var saved = HistoryState()
    override fun getState(): HistoryState = saved
    override fun loadState(state: HistoryState) { saved = state }
    override var lastPackage: String
        get() = PropertiesComponent.getInstance().getValue("adbToolbox.deepLink.lastPackage", "")
        set(value) { PropertiesComponent.getInstance().setValue("adbToolbox.deepLink.lastPackage", value) }
    override fun entries(): List<RecentDeviceLink> = saved.entries.map { RecentDeviceLink(it.uri, it.pkg, it.lastRun, it.handledBy) }
    override fun replace(entries: List<RecentDeviceLink>) {
        saved = HistoryState().apply {
            this.entries = entries.take(50).map { value -> Entry().apply {
                uri = value.uri; pkg = value.packageName; lastRun = value.lastRun; handledBy = value.handledBy
            } }.toMutableList()
        }
    }
}
