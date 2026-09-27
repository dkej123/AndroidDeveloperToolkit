package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.acme.adbtoolbox.application.logcat.LogcatPackageChoice

/** One row of Logcat's package picker: "All packages", or one app. */
internal sealed interface LogcatPickerItem {
    data object AllPackages : LogcatPickerItem

    data class App(val choice: LogcatPackageChoice) : LogcatPickerItem
}

/**
 * The popup behind Logcat's package chip: "All packages" first, then the pinned apps under a
 * "Pinned" separator, then every other app. Type-to-search matches label and package name.
 */
internal class LogcatPackagePickerStep(
    choices: List<LogcatPackageChoice>,
    private val onPick: (String?) -> Unit,
) : BaseListPopupStep<LogcatPickerItem>(
    "Show Logcat For",
    listOf(LogcatPickerItem.AllPackages) + choices.map(LogcatPickerItem::App),
) {
    private val firstPinned = choices.firstOrNull { it.pinned }
    private val firstUnpinned = choices.firstOrNull { !it.pinned }

    override fun getTextFor(value: LogcatPickerItem): String = when (value) {
        LogcatPickerItem.AllPackages -> "All packages"
        is LogcatPickerItem.App ->
            if (value.choice.label == value.choice.packageName) value.choice.packageName
            else "${value.choice.label}  ·  ${value.choice.packageName}"
    }

    override fun getSeparatorAbove(value: LogcatPickerItem): ListSeparator? {
        val choice = (value as? LogcatPickerItem.App)?.choice ?: return null
        return when {
            choice == firstPinned -> ListSeparator("Pinned")
            choice == firstUnpinned && firstPinned != null -> ListSeparator("Apps")
            else -> null
        }
    }

    override fun isSpeedSearchEnabled(): Boolean = true

    override fun onChosen(selectedValue: LogcatPickerItem, finalChoice: Boolean): PopupStep<*>? {
        onPick((selectedValue as? LogcatPickerItem.App)?.choice?.packageName)
        return FINAL_CHOICE
    }
}
