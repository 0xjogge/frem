package dk.frem.app.data

import dk.frem.app.recording.ActivitySession
import dk.frem.app.recording.RecordingState
import java.time.Instant
import java.time.ZoneId

object CsvExport {
    fun create(
        entries: List<LogEntry>,
        days: List<DaySummary>,
        workouts: List<Workout>,
        nights: List<SleepNight>,
        recordedActivities: List<ActivitySession> = emptyList(),
    ): String {
        fun escape(value: Any?): String =
            value?.toString()?.let { "\"${it.replace("\"", "\"\"")}\"" } ?: ""
        val rows =
            mutableListOf<List<Any?>>(
                listOf(
                    "record_type",
                    "date",
                    "start_time",
                    "end_time",
                    "value",
                    "unit",
                    "source",
                    "detail",
                ),
            )
        entries.forEach {
            rows +=
                listOf(
                    it.kind.lowercase(),
                    it.date,
                    null,
                    null,
                    it.value,
                    it.logKind.unit,
                    it.source,
                    it.syncState,
                )
        }
        days.forEach { d ->
            val values =
                listOf(
                    Triple("steps", d.steps, "steps"),
                    Triple("distance", d.distance, "m"),
                    Triple("active_energy", d.activeCalories, "kcal"),
                    Triple("total_energy", d.totalCalories, "kcal"),
                    Triple("floors", d.floors, "floors"),
                    Triple("elevation", d.elevation, "m"),
                    Triple("heart_rate", d.heartAverage, "bpm"),
                    Triple("resting_heart_rate", d.restingHeart, "bpm"),
                    Triple("sleep", d.sleepMinutes, "min"),
                    Triple("hydration_total", d.waterMl, "ml"),
                    Triple("active_minutes", d.activeMinutes, "min"),
                    Triple("cadence", d.cadence, "steps/min"),
                    Triple("moderate_minutes", d.moderateMinutes, "min"),
                    Triple("vigorous_minutes", d.vigorousMinutes, "min"),
                    Triple("intensity_minutes", d.intensityMinutes, "min"),
                    Triple("latest_heart_rate", d.heartLatest, "bpm"),
                )
            values
                .filter { it.second != null }
                .forEach { (type, value, unit) ->
                    rows +=
                        listOf(
                            type,
                            d.date,
                            null,
                            null,
                            value,
                            unit,
                            if (type == "distance") d.distanceSource ?: d.source else d.source,
                            if (type == "distance" && d.distanceSource == "Phone recording")
                                "estimated"
                            else null,
                        )
                }
        }
        workouts.forEach {
            rows +=
                listOf(
                    "workout",
                    Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate(),
                    Instant.ofEpochMilli(it.start),
                    Instant.ofEpochMilli(it.end),
                    it.minutes,
                    "min",
                    it.source,
                    it.title,
                )
        }
        nights.forEach {
            rows +=
                listOf(
                    "sleep_session",
                    Instant.ofEpochMilli(it.end).atZone(ZoneId.systemDefault()).toLocalDate(),
                    Instant.ofEpochMilli(it.start),
                    Instant.ofEpochMilli(it.end),
                    it.asleepMinutes,
                    "min",
                    it.source,
                    "deep=${it.deepMinutes};rem=${it.remMinutes};light=${it.lightMinutes};awake=${it.awakeMinutes}",
                )
        }
        recordedActivities
            .filter { it.state == RecordingState.FINISHED }
            .forEach { session ->
                val start = Instant.ofEpochMilli(session.start)
                val end = session.end?.let(Instant::ofEpochMilli)
                val date = start.atZone(ZoneId.of(session.zoneId)).toLocalDate()
                val detail =
                    "id=${session.id};title=${session.title};type=${session.type.name};zone=${session.zoneId}"
                val values =
                    listOf(
                        Triple("activity_distance", session.distanceMeters, "m"),
                        Triple("activity_recording_seconds", session.durationMillis / 1_000.0, "s"),
                        Triple(
                            "activity_elapsed_seconds",
                            session.end
                                ?.takeIf { it >= session.start }
                                ?.let {
                                    (it - session.start) / 1_000.0
                                },
                            "s",
                        ),
                    )
                values
                    .filter { it.second != null }
                    .forEach { (type, value, unit) ->
                        rows +=
                            listOf(
                                type,
                                date,
                                start,
                                end,
                                value,
                                unit,
                                "Recorded with Frem",
                                detail,
                            )
                    }
            }
        return rows.joinToString("\r\n", postfix = "\r\n") { row ->
            row.joinToString(",", transform = ::escape)
        }
    }
}
