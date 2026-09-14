package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.logcat.SequencedLogcatEntry

/**
 * A filtered-view change [LogcatControlsController] publishes for a presentation layer (`:intellij`'s
 * renderer, task 036) to turn into a [dev.acme.adbtoolbox.domain.logcat.LogcatEntry]-driven render
 * batch — kept as plain domain entries (never an intellij render type) so this stays UI-toolkit-free.
 * [Reset] replaces the entire visible view (new criteria, or a resync after buffer eviction outran
 * the cursor); [Delta] appends only the newly matched entries and/or advances the retention floor.
 */
sealed interface LogcatFilterUpdate {
    data class Reset(val entries: List<SequencedLogcatEntry>) : LogcatFilterUpdate

    data class Delta(
        val appended: List<SequencedLogcatEntry>,
        val oldestRetainedSequence: Long?,
    ) : LogcatFilterUpdate
}
