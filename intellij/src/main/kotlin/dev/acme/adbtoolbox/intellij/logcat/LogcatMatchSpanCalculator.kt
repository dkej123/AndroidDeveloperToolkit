package dev.acme.adbtoolbox.intellij.logcat

/**
 * Task 037's "highlight matched spans through renderer metadata": a pure, case-insensitive
 * substring search over one already-rendered row's message text (`design/README.md` §7's "Search
 * hits: amber highlight on the matched substring only") — never re-parses a raw logcat line and
 * never decides any color/paint itself (that stays task 048's). Every non-overlapping occurrence of
 * [query] is reported; a blank [query] reports no spans.
 */
object LogcatMatchSpanCalculator {

    fun spansFor(text: String, query: String): List<LogcatMatchSpan> {
        if (query.isBlank()) return emptyList()
        val spans = mutableListOf<LogcatMatchSpan>()
        var index = text.indexOf(query, startIndex = 0, ignoreCase = true)
        while (index >= 0) {
            spans += LogcatMatchSpan(index, index + query.length)
            index = text.indexOf(query, startIndex = index + query.length, ignoreCase = true)
        }
        return spans
    }
}
