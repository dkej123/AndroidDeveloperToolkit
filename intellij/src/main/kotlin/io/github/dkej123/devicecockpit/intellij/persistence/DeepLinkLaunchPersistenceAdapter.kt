package io.github.dkej123.devicecockpit.intellij.persistence

import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkLaunchMemory
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkLaunchValues

/** One remembered deep link (an `XmlSerializer` bean): its app, template, values and extra query parameters. */
class DeepLinkLaunchEntry {
    var packageName: String = ""
    var template: String = ""
    var parameters: MutableMap<String, String> = linkedMapOf()
    var extraKeys: MutableList<String> = mutableListOf()
    var extraValues: MutableList<String> = mutableListOf()
}

/** The deep-link launcher slice of [DeviceCockpitProjectState], most recently used last. */
class DeepLinkLaunchState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var entries: MutableList<DeepLinkLaunchEntry> = mutableListOf()

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * The `:intellij` [DeepLinkLaunchMemory]: values typed in App details › Deep Links, per package and
 * template, kept in the project's state file. At most [MAX_ENTRIES], dropping the least recently saved.
 */
class DeepLinkLaunchPersistenceAdapter(
    private val projectState: DeviceCockpitProjectState,
) : DeepLinkLaunchMemory {

    override fun load(packageName: String, template: String): DeepLinkLaunchValues? {
        val state = projectState.state.deepLinkLaunches
        if (state.schemaVersion != DeepLinkLaunchState.CURRENT_SCHEMA_VERSION) return null
        val entry = state.entries.lastOrNull { it.packageName == packageName && it.template == template } ?: return null
        return DeepLinkLaunchValues(entry.parameters.toMap(), entry.extraKeys.zip(entry.extraValues))
    }

    override fun save(packageName: String, template: String, values: DeepLinkLaunchValues) {
        val state = projectState.state.deepLinkLaunches.takeIf { it.schemaVersion == DeepLinkLaunchState.CURRENT_SCHEMA_VERSION }
            ?: DeepLinkLaunchState().also { projectState.state.deepLinkLaunches = it }
        state.entries.removeAll { it.packageName == packageName && it.template == template }
        if (values.parameters.values.any(String::isNotEmpty) || values.extras.isNotEmpty()) {
            state.entries += DeepLinkLaunchEntry().apply {
                this.packageName = packageName
                this.template = template
                parameters = LinkedHashMap(values.parameters)
                extraKeys = values.extras.map { it.first }.toMutableList()
                extraValues = values.extras.map { it.second }.toMutableList()
            }
        }
        while (state.entries.size > MAX_ENTRIES) state.entries.removeAt(0)
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
