package dev.acme.adbtoolbox.intellij.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** IntelliJ-loaded production icons supplied under `design/icons`. */
object AdbToolboxIcons {
    val toolWindow: Icon = load("/icons/adbToolbox.svg")

    object Actions {
        val authorize: Icon = load("/icons/actions/authorize.svg")
        val autoscroll: Icon = load("/icons/actions/autoscroll.svg")
        val clearData: Icon = load("/icons/actions/clearData.svg")
        val density: Icon = load("/icons/actions/density.svg")
        val filter: Icon = load("/icons/actions/filter.svg")
        val fontScale: Icon = load("/icons/actions/fontScale.svg")
        val forceStop: Icon = load("/icons/actions/forceStop.svg")
        val mirror: Icon = load("/icons/actions/mirror.svg")
        val options: Icon = load("/icons/actions/options.svg")
        val pause: Icon = load("/icons/actions/pause.svg")
        val proxy: Icon = load("/icons/actions/proxy.svg")
        val record: Icon = load("/icons/actions/record.svg")
        val refresh: Icon = load("/icons/actions/refresh.svg")
        val restart: Icon = load("/icons/actions/restart.svg")
        val resume: Icon = load("/icons/actions/resume.svg")
        val screenshot: Icon = load("/icons/actions/screenshot.svg")
        val search: Icon = load("/icons/actions/search.svg")
        val uninstall: Icon = load("/icons/actions/uninstall.svg")
        val wrap: Icon = load("/icons/actions/wrap.svg")
    }

    /** Complete loadable catalog, used by the package test and future feature call sites. */
    val all: List<Icon> = listOf(
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

    private fun load(path: String): Icon = IconLoader.getIcon(path, AdbToolboxIcons::class.java)
}
