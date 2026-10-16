package dk.frem.app.domain

import dk.frem.app.data.LogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

fun measurementTime(entry: LogEntry): ZonedDateTime {
    val zone = ZoneId.of(entry.zone)
    val noon = entry.localDate.atTime(12, 0).atZone(zone)
    val saved = Instant.ofEpochMilli(entry.timestamp)
    return if (noon.toInstant() > saved) saved.atZone(zone) else noon
}
