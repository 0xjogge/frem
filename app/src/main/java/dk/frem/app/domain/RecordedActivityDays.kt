package dk.frem.app.domain

import dk.frem.app.data.DaySummary
import dk.frem.app.recording.ActivityDayMetrics

data class RecordedActivityDays(
    val days: List<DaySummary>,
    val nativeSources: Map<String, Map<String, String>>,
)

/**
 * Choose a source independently per metric/day. Never add a recording to an overlapping HC total.
 */
fun recordedActivityDays(
    health: List<DaySummary>,
    native: List<ActivityDayMetrics>,
): RecordedActivityDays {
    val result = health.associateBy { it.date }.toMutableMap()
    val sources = mutableMapOf<String, Map<String, String>>()
    native
        .groupBy { it.date }
        .forEach { (date, rows) ->
            val day = result[date] ?: DaySummary(date)
            val labels = mutableMapOf<String, String>()
            fun choose(key: String, imported: Double?, recorded: Double?, method: String): Double? {
                if (imported != null) return imported
                if (recorded != null) labels[key] = "Frem recordings · $method"
                return recorded
            }
            fun sum(read: (ActivityDayMetrics) -> Double?): Double? =
                rows.mapNotNull(read).takeIf { it.isNotEmpty() }?.sum()
            val duration = rows.sumOf { it.cadenceMillis }
            val active =
                if (duration > 0) rows.sumOf { it.moderateMillis + it.vigorousMillis } / 60_000.0
                else null
            val elevationSource =
                rows.mapNotNull { it.elevationSource }.distinct().joinToString(" + ").lowercase()
            val useNativeIntensity = day.activeMinutes == null && active != null
            result[date] =
                day.copy(
                    cadence =
                        choose(
                            "cadence",
                            day.cadence,
                            if (duration > 0) rows.sumOf { it.cadenceSteps } * 60_000 / duration
                            else null,
                            "moving step rate",
                        ),
                    activeMinutes =
                        choose("active_minutes", day.activeMinutes, active, "estimated"),
                    moderateMinutes =
                        if (useNativeIntensity) rows.sumOf { it.moderateMillis } / 60_000.0
                        else day.moderateMinutes,
                    vigorousMinutes =
                        if (useNativeIntensity) rows.sumOf { it.vigorousMillis } / 60_000.0
                        else day.vigorousMinutes,
                    intensityMinutes =
                        if (useNativeIntensity)
                            rows.sumOf { it.moderateMillis + 2 * it.vigorousMillis } / 60_000
                        else day.intensityMinutes,
                    floors = choose("floors", day.floors, sum { it.floors }, "barometer + steps"),
                    elevation =
                        choose(
                            "elevation",
                            day.elevation,
                            sum { it.ascentMeters },
                            elevationSource,
                        ),
                    elevationLost =
                        choose(
                            "elevation_lost",
                            day.elevationLost,
                            sum { it.descentMeters },
                            elevationSource,
                        ),
                )
            sources[date] = labels
        }
    return RecordedActivityDays(result.values.sortedByDescending { it.date }, sources)
}
