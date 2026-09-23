package dev.acme.adbtoolbox.intellij.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** IntelliJ-loaded production icons supplied under `design/icons`. */
object AdbToolboxIcons {
    val toolWindow: Icon get() = load("/icons/adbToolbox.svg")

    object Actions {
        val authorize: Icon get() = load("/icons/actions/authorize.svg")
        val autoscroll: Icon get() = load("/icons/actions/autoscroll.svg")
        val clearData: Icon get() = load("/icons/actions/clearData.svg")
        val density: Icon get() = load("/icons/actions/density.svg")
        val filter: Icon get() = load("/icons/actions/filter.svg")
        val fontScale: Icon get() = load("/icons/actions/fontScale.svg")
        val forceStop: Icon get() = load("/icons/actions/forceStop.svg")
        val mirror: Icon get() = load("/icons/actions/mirror.svg")
        val options: Icon get() = load("/icons/actions/options.svg")
        val pause: Icon get() = load("/icons/actions/pause.svg")
        val proxy: Icon get() = load("/icons/actions/proxy.svg")
        val record: Icon get() = load("/icons/actions/record.svg")
        val refresh: Icon get() = load("/icons/actions/refresh.svg")
        val restart: Icon get() = load("/icons/actions/restart.svg")
        val resume: Icon get() = load("/icons/actions/resume.svg")
        val screenshot: Icon get() = load("/icons/actions/screenshot.svg")
        val search: Icon get() = load("/icons/actions/search.svg")
        val uninstall: Icon get() = load("/icons/actions/uninstall.svg")
        val wrap: Icon get() = load("/icons/actions/wrap.svg")
    }

    /** Complete loadable catalog, used by the package test and future feature call sites. */
    val all: List<Icon> get() = listOf(
        toolWindow,
        Actions.authorize,
        Actions.autoscroll,
        Actions.clearData,
        Actions.density,
        Actions.filter,
        Actions.fontScale,
        Actions.forceStop,
        Actions.mirror,
        Actions.options,
        Actions.pause,
        Actions.proxy,
        Actions.record,
        Actions.refresh,
        Actions.restart,
        Actions.resume,
        Actions.screenshot,
        Actions.search,
        Actions.uninstall,
        Actions.wrap,
    )

    private fun load(path: String): Icon = requireNotNull(IconLoader.getIcon(path, AdbToolboxIcons::class.java)) {
        "Missing icon resource: $path"
    }
}
