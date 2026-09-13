package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * The root project-level `PersistentStateComponent` (ADR 0006: project scope, one `adbToolbox.xml`
 * file per project). Composed of feature-local state-carrier slices — [DeviceSelectionState]
 * (task 009), [NavigationPersistenceState] (task 012), [SettingsPersistenceState] (task 038), and
 * [AppsSelectionState] (task 022) — each feature reads/writes only its own slice through its own
 * port/adapter pair (e.g. [DeviceSelectionPersistenceAdapter], [NavigationPersistenceAdapter],
 * [SettingsPersistenceAdapter], [AppsSelectionPersistenceAdapter]); a future feature adds a new
 * slice/property here, it does not reach into another feature's.
 */
@Service(Service.Level.PROJECT)
@State(name = "AdbToolboxProjectState", storages = [Storage("adbToolbox.xml")])
class AdbToolboxProjectState : PersistentStateComponent<AdbToolboxProjectState.State> {

    class State {
        var deviceSelection: DeviceSelectionState = DeviceSelectionState()
        var navigation: NavigationPersistenceState = NavigationPersistenceState()
        var settings: SettingsPersistenceState = SettingsPersistenceState()
        var apps: AppsSelectionState = AppsSelectionState()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    companion object {
        fun getInstance(project: Project): AdbToolboxProjectState = project.service()
    }
}
