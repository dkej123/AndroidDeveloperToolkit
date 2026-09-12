package dev.acme.adbtoolbox.intellij.persistence

/**
 * The Apps-selection slice of [AdbToolboxProjectState] (`design/README.md`'s State model:
 * `selectedPackage`, task 022). A plain mutable bean (public `var`s, no-arg constructor) as
 * IntelliJ's `XmlSerializer` requires — mirrors [DeviceSelectionState]'s shape.
 *
 * [selectedDeviceSerial] is persisted alongside [selectedPackageName] (rather than persisting the
 * package name alone) so [resolveAppsSelection] can tell a same-device selection apart from a
 * different device's leftover selection on restore — a selection never silently applies to the
 * wrong device across an IDE restart.
 *
 * [schemaVersion] lets [resolveAppsSelection] recognize XML written by a shape this class no longer
 * matches and degrade to "no selection" instead of misinterpreting stale data — bump
 * [CURRENT_SCHEMA_VERSION] whenever this shape changes incompatibly.
 */
class AppsSelectionState {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var selectedDeviceSerial: String? = null
    var selectedPackageName: String? = null

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
