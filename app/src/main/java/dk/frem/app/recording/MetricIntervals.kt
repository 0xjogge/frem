package dk.frem.app.recording

import java.time.Instant
import java.time.ZoneId

/** Calendar boundaries, including DST; durations always remain real elapsed milliseconds. */
internal fun splitMetricInterval(
    start: Long,
    end: Long,
    zone: ZoneId,
    consume: (String, Long) -> Unit,
) {
    var cursor = start
    while (cursor < end) {
        val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val next = minOf(end, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        consume(date.toString(), next - cursor)
        cursor = next
    }
}
