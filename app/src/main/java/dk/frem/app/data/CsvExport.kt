package dk.frem.app.data

import dk.frem.app.hearing.NoiseSession
import dk.frem.app.recording.ActivityDayMetrics
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
        nativeActivityMetrics: List<ActivityDayMetrics> = emptyList(),
        noiseSessions: List<NoiseSession> = emptyList(),
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
                    Triple("elevation_lost", d.elevationLost, "m"),
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
                        Triple("activity_elevation_gained", session.ascentMeters, "m"),
                        Triple("activity_elevation_lost", session.descentMeters, "m"),
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
        nativeActivityMetrics.forEach { day ->
            listOf(
                    Triple("recorded_steps", day.steps?.toDouble(), "steps"),
                    Triple("recorded_cadence", day.cadence, "steps/min"),
                    Triple("recorded_active_minutes", day.activeMinutes, "min"),
                    Triple(
                        "recorded_moderate_minutes",
                        if (day.cadenceMillis > 0) day.moderateMillis / 60_000.0 else null,
                        "min",
                    ),
                    Triple(
                        "recorded_vigorous_minutes",
                        if (day.cadenceMillis > 0) day.vigorousMillis / 60_000.0 else null,
                        "min",
                    ),
                    Triple("recorded_floors", day.floors, "floors"),
                    Triple("recorded_elevation_gained", day.ascentMeters, "m"),
                    Triple("recorded_elevation_lost", day.descentMeters, "m"),
                )
                .filter { it.second != null }
                .forEach { (type, value, unit) ->
                    rows +=
                        listOf(
                            type,
                            day.date,
                            null,
                            null,
                            value,
                            unit,
                            "Recorded with Frem",
                            "session=${day.sessionId};coverage=recorded activities;intensity=estimated;cadence_moving_ms=${day.cadenceMillis};elevation_source=${day.elevationSource}",
                        )
                }
        }
        noiseSessions
            .filter { it.state != "RECORDING" }
            .forEach { session ->
                listOf(
                        Triple("environmental_noise_leq", session.leqDbA, "dB(A)"),
                        Triple(
                            "environmental_noise_uncalibrated",
                            session.rawLeq.takeIf { session.calibrationOffsetDb == null },
                            "dBFS(A)",
                        ),
                        Triple(
                            "environmental_noise_measured_seconds",
                            session.measuredMillis / 1000.0,
                            "s",
                        ),
                        Triple(
                            "environmental_noise_unmeasured_seconds",
                            session.unmeasuredMillis / 1000.0,
                            "s",
                        ),
                        Triple("environmental_noise_dose", session.dosePercent, "%"),
                    )
                    .filter { it.second != null }
                    .forEach { (type, value, unit) ->
                        rows +=
                            listOf(
                                type,
                                Instant.ofEpochMilli(session.start)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate(),
                                Instant.ofEpochMilli(session.start),
                                session.end?.let(Instant::ofEpochMilli),
                                value,
                                unit,
                                "Frem microphone",
                                "session=${session.id};state=${session.state};input=${session.inputKey};calibration_offset_db=${session.calibrationOffsetDb};reference=85dBA/8h/3dB;coverage=measured intervals",
                            )
                    }
            }
        return rows.joinToString("\r\n", postfix = "\r\n") { row ->
            row.joinToString(",", transform = ::escape)
        }
    }
}
