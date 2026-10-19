package dk.frem.app.data

import dk.frem.app.recording.ActivitySession
import dk.frem.app.recording.ActivityType
import dk.frem.app.recording.RecordingState
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CsvExportTest {
    @Test
    fun exportPreservesUnitsAndEscapesSourceNamesWithoutInventingMissingMetrics() {
        val entry =
            LogEntry(kind = "WEIGHT", date = "2026-10-14", value = 80.5, source = "Scale, \"home\"")
        val csv =
            CsvExport.create(
                listOf(entry),
                listOf(DaySummary("2026-10-14", steps = 0, distance = null)),
                emptyList(),
                emptyList(),
            )
        assertTrue(csv.contains("\"80.5\",\"kg\""))
        assertTrue(csv.contains("\"Scale, \"\"home\"\"\""))
        assertTrue(csv.contains("\"steps\",\"2026-10-14\",,,\"0\""))
        assertFalse(csv.contains("\"distance\""))
    }

    @Test
    fun phoneDistanceIsExportedInMetersWithItsOwnSourceAndEstimateLabel() {
        val csv =
            CsvExport.create(
                emptyList(),
                listOf(
                    DaySummary(
                        "2026-10-19",
                        steps = 5000,
                        distance = 2100.5,
                        source = "Phone recording + Health Connect",
                        distanceSource = "Phone recording",
                    ),
                ),
                emptyList(),
                emptyList(),
            )
        assertTrue(csv.contains("\"2100.5\",\"m\",\"Phone recording\",\"estimated\""))
        assertTrue(csv.contains("\"5000\",\"steps\",\"Phone recording + Health Connect\""))
    }

    @Test
    fun fourArgumentCallMatchesExplicitEmptyActivitiesAndPreservesExistingRecords() {
        val entries = listOf(LogEntry(kind = "WEIGHT", date = "2026-10-19", value = 80.5))
        val days = listOf(DaySummary("2026-10-19", steps = 123))
        val workouts = listOf(Workout("imported", "Lunch walk", 1, 0, 300_000, "Health Connect"))
        val nights = listOf(SleepNight("night", 0, 600_000, 10.0, null, null, null, null, "Watch"))

        val csv = CsvExport.create(entries, days, workouts, nights)

        assertEquals(csv, CsvExport.create(entries, days, workouts, nights, emptyList()))
        val rows = parseCsv(csv)
        assertEquals(
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
            rows.first(),
        )
        assertEquals(
            listOf("weight", "steps", "workout", "sleep_session"),
            rows.drop(1).map { it[0] },
        )
        assertTrue(rows.all { it.size == 8 })
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun finishedActivityExportsMetersAndSeparateSecondsWithSavedZoneAndEscapedMetadata() {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val session = finishedSession().copy(title = "Sø, \"skov\" 🌲\r\nEvening; walk")
            val csv =
                CsvExport.create(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(session),
                )
            val rows = parseCsv(csv).drop(1)

            assertEquals(3, rows.size)
            assertEquals(
                listOf(
                    "activity_distance",
                    "activity_recording_seconds",
                    "activity_elapsed_seconds",
                ),
                rows.map { it[0] },
            )
            assertEquals(listOf("1234.5", "60.25", "125.75"), rows.map { it[4] })
            assertEquals(listOf("m", "s", "s"), rows.map { it[5] })
            rows.forEach { row ->
                assertEquals(8, row.size)
                assertEquals("2026-10-19", row[1])
                assertEquals("2026-10-18T23:30:00.125Z", row[2])
                assertEquals("2026-10-18T23:32:05.875Z", row[3])
                assertEquals("Recorded with Frem", row[6])
                assertEquals(
                    "id=${session.id};title=${session.title};type=HIKING;zone=Europe/Copenhagen",
                    row[7],
                )
            }
            assertTrue(csv.contains("Sø, \"\"skov\"\" 🌲\r\nEvening; walk"))
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun unfinishedSessionsAreExcludedAndFinishedIdsRemainDistinct() {
        val finished = finishedSession()
        val sessions =
            RecordingState.entries.map { finished.copy(id = "session-$it", state = it) } +
                finished.copy(id = "another-finished-session")

        val rows =
            parseCsv(CsvExport.create(emptyList(), emptyList(), emptyList(), emptyList(), sessions))
                .drop(1)

        assertEquals(6, rows.size)
        assertEquals(3, rows.count { it[7].startsWith("id=session-FINISHED;") })
        assertEquals(3, rows.count { it[7].startsWith("id=another-finished-session;") })
    }

    @Test
    fun activitiesDoNotChangeDayTotalsOrBecomeImportedWorkouts() {
        val rows =
            parseCsv(
                    CsvExport.create(
                        emptyList(),
                        listOf(DaySummary("2026-10-19", distance = 2500.0, activeMinutes = 5.0)),
                        listOf(
                            Workout("imported", "Imported run", 1, 0, 300_000, "Health Connect"),
                        ),
                        emptyList(),
                        listOf(finishedSession()),
                    ),
                )
                .drop(1)

        assertEquals(6, rows.size)
        assertEquals("2500.0", rows.single { it[0] == "distance" }[4])
        assertEquals("5.0", rows.single { it[0] == "active_minutes" }[4])
        val workout = rows.single { it[0] == "workout" }
        assertEquals("5", workout[4])
        assertEquals("Health Connect", workout[6])
        assertEquals("Imported run", workout[7])
        assertEquals(3, rows.count { it[0].startsWith("activity_") })
    }

    @Test
    fun missingOrInvalidEndDoesNotInventElapsedTime() {
        val session = finishedSession().copy(distanceMeters = 0.0, durationMillis = 0)
        for (end in listOf(null, session.start - 1)) {
            val rows =
                parseCsv(
                        CsvExport.create(
                            emptyList(),
                            emptyList(),
                            emptyList(),
                            emptyList(),
                            listOf(session.copy(end = end)),
                        ),
                    )
                    .drop(1)
            assertEquals(
                listOf("activity_distance", "activity_recording_seconds"),
                rows.map { it[0] },
            )
            assertEquals(listOf("0.0", "0.0"), rows.map { it[4] })
            if (end == null) assertTrue(rows.all { it[3].isEmpty() })
        }
    }

    @Test
    fun nativeSubtotalsAreExportedWithTheirScopeWithoutAlteringImportedTotals() {
        val native =
            dk.frem.app.recording.ActivityDayMetrics(
                "walk",
                "2026-10-19",
                steps = 120,
                cadenceSteps = 120.0,
                cadenceMillis = 60_000,
                moderateMillis = 30_000,
                ascentMeters = 9.0,
                descentMeters = 3.0,
                floorAscentMeters = 6.0,
                elevationSource = "Barometer",
            )
        val rows =
            parseCsv(
                    CsvExport.create(
                        emptyList(),
                        listOf(DaySummary(native.date, floors = 0.0)),
                        emptyList(),
                        emptyList(),
                        nativeActivityMetrics = listOf(native),
                    ),
                )
                .drop(1)
        assertEquals("0.0", rows.single { it[0] == "floors" }[4])
        assertEquals("2.0", rows.single { it[0] == "recorded_floors" }[4])
        assertEquals("120.0", rows.single { it[0] == "recorded_cadence" }[4])
        assertTrue(
            rows
                .filter { it[0].startsWith("recorded_") }
                .all {
                    it[6] == "Recorded with Frem" && it[7].contains("coverage=recorded activities")
                },
        )
    }

    private fun finishedSession(): ActivitySession {
        val start = Instant.parse("2026-10-18T23:30:00.125Z").toEpochMilli()
        return ActivitySession(
            id = "f36d5a82-3a86-4988-9e3b-d315302eeb04",
            title = "Evening walk",
            type = ActivityType.HIKING,
            start = start,
            end = start + 125_750,
            zoneId = "Europe/Copenhagen",
            state = RecordingState.FINISHED,
            distanceMeters = 1234.5,
            durationMillis = 60_250,
        )
    }

    @Test
    fun noiseExportsDoNotLabelUncalibratedLevelsAsSoundPressureOrDose() {
        val raw =
            dk.frem.app.hearing.NoiseSession(
                "raw",
                0,
                state = "FINISHED",
                measuredMillis = 1000,
                energyMillis = 10.0,
            )
        val rows =
            parseCsv(
                    CsvExport.create(
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        noiseSessions = listOf(raw),
                    ),
                )
                .drop(1)
        assertTrue(rows.any { it[0] == "environmental_noise_uncalibrated" && it[5] == "dBFS(A)" })
        assertFalse(
            rows.any { it[0] == "environmental_noise_leq" || it[0] == "environmental_noise_dose" },
        )
        val calibrated = raw.copy(calibrationOffsetDb = 100.0, doseFraction = .1)
        val measured =
            parseCsv(
                    CsvExport.create(
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        noiseSessions = listOf(calibrated),
                    ),
                )
                .drop(1)
        assertEquals("10.0", measured.single { it[0] == "environmental_noise_dose" }[4])
        assertTrue(measured.all { it[7].contains("coverage=measured intervals") })
    }

    /** Read CSV fields, including quoted CRLFs, instead of treating every line as a record. */
    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index++]
            when {
                char == '"' -> {
                    if (quoted && index < csv.length && csv[index] == '"') {
                        field.append('"')
                        index++
                    } else quoted = !quoted
                }
                quoted -> field.append(char)
                char == ',' -> {
                    row += field.toString()
                    field.setLength(0)
                }
                char == '\r' -> {
                    assertTrue(index < csv.length && csv[index++] == '\n')
                    row += field.toString()
                    rows += row.toList()
                    row.clear()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
        }
        assertFalse(quoted)
        assertTrue(row.isEmpty())
        assertEquals(0, field.length)
        return rows
    }
}
