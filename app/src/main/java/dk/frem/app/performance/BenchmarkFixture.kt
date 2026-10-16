package dk.frem.app.performance

import dk.frem.app.data.*
import dk.frem.app.ui.AppData
import java.time.LocalDate
import java.time.ZoneId

/** Composition-only stress data. Nothing is inserted into Room or Health Connect. */
object BenchmarkFixture {
    fun create(): AppData {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val days =
            (0..365).map { ago ->
                DaySummary(
                    today.minusDays(ago.toLong()).toString(),
                    steps = 7000L + ago % 6000,
                    distance = 5500.0,
                    activeCalories = 250.0,
                )
            }
        val weights =
            (0..999).map { ago ->
                LogEntry(
                    id = "fixture-weight-$ago",
                    kind = "WEIGHT",
                    date = today.minusDays(ago.toLong()).toString(),
                    value = 80.0 + ago % 7 / 10.0,
                    source = "PERFORMANCE FIXTURE",
                    syncState = "synced",
                )
            }
        val water =
            (0..3999).map { n ->
                LogEntry(
                    id = "fixture-water-$n",
                    kind = "HYDRATION",
                    date = today.minusDays((n / 10).toLong()).toString(),
                    value = 250.0,
                    source = "PERFORMANCE FIXTURE",
                    syncState = "synced",
                )
            }
        val workouts =
            (0..499).map { n ->
                val start =
                    today
                        .minusDays(n.toLong())
                        .atTime(10, 0)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                Workout(
                    "fixture-workout-$n",
                    "Walk",
                    79,
                    start,
                    start + 30 * 60_000,
                    "PERFORMANCE FIXTURE",
                )
            }
        val nights =
            (0..365).map { n ->
                val end =
                    today.minusDays(n.toLong()).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
                SleepNight(
                    "fixture-sleep-$n",
                    end - 8 * 3600_000,
                    end,
                    460.0,
                    90.0,
                    100.0,
                    270.0,
                    20.0,
                    "PERFORMANCE FIXTURE",
                )
            }
        return AppData(
            days,
            weights + water,
            workouts,
            nights,
            Preferences(),
            emptyList(),
            setOf("fixture"),
            6000,
            today,
        )
    }
}
