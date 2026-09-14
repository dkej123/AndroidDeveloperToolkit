package dev.acme.adbtoolbox.intellij.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogcatMatchSpanCalculatorTest {

    @Test
    fun `a blank query has no spans`() {
        assertEquals(emptyList<LogcatMatchSpan>(), LogcatMatchSpanCalculator.spansFor("hello world", ""))
        assertEquals(emptyList<LogcatMatchSpan>(), LogcatMatchSpanCalculator.spansFor("hello world", "   "))
    }

    @Test
    fun `a single match is reported`() {
        val spans = LogcatMatchSpanCalculator.spansFor("a timeout occurred", "timeout")
        assertEquals(listOf(LogcatMatchSpan(2, 9)), spans)
    }

    @Test
    fun `matching is case-insensitive`() {
        val spans = LogcatMatchSpanCalculator.spansFor("a TIMEOUT occurred", "timeout")
        assertEquals(listOf(LogcatMatchSpan(2, 9)), spans)
    }

    @Test
    fun `every non-overlapping occurrence is reported`() {
        val spans = LogcatMatchSpanCalculator.spansFor("ab ab ab", "ab")
        assertEquals(listOf(LogcatMatchSpan(0, 2), LogcatMatchSpan(3, 5), LogcatMatchSpan(6, 8)), spans)
    }

    @Test
    fun `no match reports no spans`() {
        assertEquals(emptyList<LogcatMatchSpan>(), LogcatMatchSpanCalculator.spansFor("hello world", "timeout"))
    }
}
