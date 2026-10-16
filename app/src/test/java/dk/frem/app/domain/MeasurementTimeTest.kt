package dk.frem.app.domain

import dk.frem.app.data.LogEntry
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MeasurementTimeTest {
    @Test
    fun morningEntryUsesItsSavedInstantInsteadOfFutureNoon() {
        val saved = Instant.parse("2026-10-14T06:00:00Z")
        val entry =
            LogEntry(
                kind = "WEIGHT",
                date = "2026-10-14",
                value = 80.0,
                zone = "Europe/Copenhagen",
                timestamp = saved.toEpochMilli(),
            )
        assertEquals(saved, measurementTime(entry).toInstant())
        assertEquals("2026-10-14", measurementTime(entry).toLocalDate().toString())
    }

    @Test
    fun historicalDateKeepsOriginalTimeZoneAcrossDaylightSavingBoundary() {
        val saved = Instant.parse("2026-10-27T12:00:00Z").toEpochMilli()
        val before =
            LogEntry(
                kind = "WEIGHT",
                date = "2026-10-24",
                value = 80.0,
                zone = "Europe/Copenhagen",
                timestamp = saved,
            )
        val after = before.copy(date = "2026-10-25")
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), measurementTime(before).toInstant())
        assertEquals(Instant.parse("2026-10-25T11:00:00Z"), measurementTime(after).toInstant())
    }
}
