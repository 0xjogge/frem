package dk.frem.app.domain

import dk.frem.app.data.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class InsightsTest {
    private val today = LocalDate.of(2026, 10, 14)

    private fun day(ago: Long, steps: Long?) =
        DaySummary(today.minusDays(ago).toString(), steps = steps)

    @Test
    fun weeklyComparisonRequiresTwoCompleteWindowsAndExcludesToday() {
        val days = (1L..7L).map { day(it, 1200) } + (8L..14L).map { day(it, 1000) } + day(0, 99000)
        val highlight = Insights.highlights(days, emptyList(), 8000, today).first()
        assertEquals("20% more steps", highlight.title)
        assertFalse(
            Insights.highlights(
                    days.filter { it.date != today.minusDays(10).toString() },
                    emptyList(),
                    8000,
                    today,
                )
                .any { it.title.contains("%") },
        )
    }

    @Test
    fun unknownDaysBreakStreaksButTodayDoesNotBreakAnOngoingStreak() {
        val days = listOf(day(0, 100), day(1, 8000), day(2, 9000), day(3, null), day(4, 10000))
        assertEquals(2, Insights.records(days, 8000, today).streak)
        assertNull(Insights.records(days, 8000, today).average)
        assertEquals(3, Insights.records(days + day(0, 8000), 8000, today).streak)
    }

    @Test
    fun genuineZeroIsIncludedInAverageWhileMissingDataIsNotInvented() {
        val days = (1L..7L).map { day(it, if (it == 7L) 0 else 700) }
        assertEquals(600L, Insights.records(days, 8000, today).average)
        assertNull(Insights.records(days.dropLast(1), 8000, today).average)
    }

    @Test
    fun personalBestUsesCompleteDaysAcrossYearBoundary() {
        val yearEnd = LocalDate.of(2027, 1, 1)
        val days =
            listOf(DaySummary("2027-01-01", steps = 25000), DaySummary("2026-12-31", steps = 12000))
        assertEquals("2026-12-31", Insights.records(days, 8000, yearEnd).bestDay?.date)
    }

    @Test
    fun weightTrendUsesCalendarWindowAndAveragesSameDayReadingsFirst() {
        fun weight(date: String, value: Double) =
            LogEntry(kind = "WEIGHT", date = date, value = value)
        val trend =
            Insights.weightTrend(
                listOf(
                    weight("2026-10-01", 100.0),
                    weight("2026-10-08", 80.0),
                    weight("2026-10-08", 82.0),
                    weight("2026-10-09", 79.0),
                ),
            )
        assertEquals(81.0, trend[1].second, 0.001)
        assertEquals(80.0, trend[2].second, 0.001)
    }

    @Test
    fun sameTimeComparisonRequiresBaselineAndIncludesExactDifference() {
        val highlights = Insights.highlights(listOf(day(0, 1500)), emptyList(), 8000, today, 1000)
        assertTrue(highlights.any { it.detail.contains("500 more steps") })
        assertFalse(
            Insights.highlights(listOf(day(0, 1500)), emptyList(), 8000, today, null).any {
                it.title.contains("pace")
            },
        )
    }
}
