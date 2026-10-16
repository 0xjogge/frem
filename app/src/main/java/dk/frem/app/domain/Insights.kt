package dk.frem.app.domain

import dk.frem.app.data.*
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

object Insights {
    fun records(days: List<DaySummary>, goal: Int, today: LocalDate): StepRecords {
        val byDate = days.associateBy { it.localDate }
        var cursor = if ((byDate[today]?.steps ?: -1) >= goal) today else today.minusDays(1)
        var streak = 0
        while ((byDate[cursor]?.steps ?: -1) >= goal) {
            streak++
            cursor = cursor.minusDays(1)
        }
        val lastWeek = (1L..7L).mapNotNull { byDate[today.minusDays(it)]?.steps }
        return StepRecords(
            streak,
            days.filter { it.localDate < today && it.steps != null }.maxByOrNull { it.steps!! },
            lastWeek.count { it >= goal },
            if (lastWeek.size == 7) lastWeek.average().toLong() else null,
        )
    }

    fun highlights(
        days: List<DaySummary>,
        entries: List<LogEntry>,
        goal: Int,
        today: LocalDate,
        sameTimeAverage: Long? = null,
    ): List<Highlight> {
        val byDate = days.associateBy { it.localDate }
        val output = mutableListOf<Highlight>()
        val recent = (1L..7L).map { byDate[today.minusDays(it)]?.steps }
        val previous = (8L..14L).map { byDate[today.minusDays(it)]?.steps }
        if (recent.all { it != null } && previous.all { it != null }) {
            val now = recent.filterNotNull().average()
            val before = previous.filterNotNull().average()
            if (before > 0) {
                val change = ((now - before) / before * 100).roundToInt()
                output +=
                    Highlight(
                        if (change == 0) "A steady week"
                        else "${abs(change)}% ${if (change > 0) "more" else "fewer"} steps",
                        "Your average across the last 7 complete days is ${now.toLong()} steps, compared with ${before.toLong()} in the previous 7.",
                    )
            }
        }
        val current = byDate[today]?.steps
        if (current != null && sameTimeAverage != null && sameTimeAverage > 0) {
            val delta = current - sameTimeAverage
            output +=
                Highlight(
                    if (delta >= 0) "Ahead of your usual pace" else "Room to move today",
                    "You have ${abs(delta)} ${if (delta >= 0) "more" else "fewer"} steps than your 7-day average at this time of day.",
                )
        }
        val records = records(days, goal, today)
        if (recent.all { it != null })
            output +=
                Highlight(
                    "${records.goalsLastWeek} goal days this week",
                    "You reached your $goal-step goal on ${records.goalsLastWeek} of the last 7 complete days.",
                )
        if (records.streak >= 2)
            output +=
                Highlight(
                    "${records.streak} days moving forward",
                    "Your step-goal streak is ${records.streak} days. One day at a time.",
                )
        val month = days.filter {
            it.localDate.month == today.month &&
                it.localDate.year == today.year &&
                it.localDate < today &&
                it.steps != null
        }
        month
            .maxByOrNull { it.steps!! }
            ?.takeIf { month.size >= 3 }
            ?.let {
                output +=
                    Highlight(
                        "Your most active day",
                        "${it.localDate.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.localDate.dayOfMonth} leads this month with ${it.steps} steps.",
                    )
            }
        val distanceRecent = (1L..7L).map { byDate[today.minusDays(it)]?.distance }
        val distancePrevious = (8L..14L).map { byDate[today.minusDays(it)]?.distance }
        if (distanceRecent.all { it != null } && distancePrevious.all { it != null }) {
            val change =
                (distanceRecent.filterNotNull().sum() - distancePrevious.filterNotNull().sum()) /
                    1000
            output +=
                Highlight(
                    "Your week in distance",
                    "You covered ${"%.1f".format(abs(change))} km ${if (change >= 0) "more" else "less"} than the previous week.",
                    "Distance",
                )
        }
        val weights = entries.filter { it.logKind == LogKind.WEIGHT }.sortedBy { it.date }
        if (weights.size >= 2) {
            val newest = weights.last()
            val oldest = weights.firstOrNull { it.localDate >= newest.localDate.minusDays(30) }
            if (oldest != null && oldest.id != newest.id) {
                val delta = newest.value - oldest.value
                output +=
                    Highlight(
                        "Your weight over time",
                        "${"%+.1f".format(delta)} kg between ${oldest.localDate} and ${newest.localDate}. Your chart shows the full picture.",
                        "Weight",
                    )
            }
        }
        return output
    }

    fun weightTrend(entries: List<LogEntry>): List<Pair<LocalDate, Double>> {
        val points =
            entries
                .filter { it.logKind == LogKind.WEIGHT }
                .groupBy { it.localDate }
                .mapValues { (_, values) -> values.map { it.value }.average() }
                .toSortedMap()
        val window = ArrayDeque<Pair<LocalDate, Double>>()
        var sum = 0.0
        return points.map { (date, value) ->
            val cutoff = date.minusDays(6)
            while (window.isNotEmpty() && window.first().first < cutoff) sum -=
                window.removeFirst().second
            window.addLast(date to value)
            sum += value
            date to sum / window.size
        }
    }
}
