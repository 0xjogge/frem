package dk.frem.app.data

import dk.frem.app.recording.ActivitySession
import dk.frem.app.recording.TrackPoint
import java.math.BigDecimal
import java.time.Instant

/**
 * GPX 1.1 with local activity metadata in the `urn:dk.frem:gpx:1` extension namespace. Root
 * extensions contain the stable session ID, activity enum, time zone and durations in seconds.
 */
object GpxExport {
    fun create(session: ActivitySession, points: List<TrackPoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append(
            "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:frem=\"urn:dk.frem:gpx:1\" version=\"1.1\" creator=\"Frem\">\n",
        )
        append("  <metadata><time>${Instant.ofEpochMilli(session.start)}</time></metadata>\n")
        append("  <trk>\n")
        append("    <name>${xml(session.title)}</name>\n")
        append("    <type>${xml(session.type.name)}</type>\n")

        // Sequence is the recording order, even across clock changes or a resumed recording.
        val ordered =
            points.asSequence().filter { it.sessionId == session.id }.sortedBy { it.sequence }
        var segment: Int? = null
        for (point in ordered) {
            if (!validCoordinates(point)) {
                // An omitted bad fix must not join the points on either side of it.
                if (segment != null) append("    </trkseg>\n")
                segment = null
                continue
            }
            if (segment != point.segment) {
                if (segment != null) append("    </trkseg>\n")
                append("    <trkseg>\n")
                segment = point.segment
            }
            append(
                "      <trkpt lat=\"${decimal(point.latitude)}\" " +
                    "lon=\"${decimal(point.longitude)}\">\n",
            )
            point.altitudeMeters
                ?.takeIf { it.isFinite() }
                ?.let {
                    append("        <ele>${decimal(it)}</ele>\n")
                }
            append("        <time>${Instant.ofEpochMilli(point.recordedAt)}</time>\n")
            append("      </trkpt>\n")
        }
        if (segment != null) append("    </trkseg>\n")
        append("  </trk>\n")
        // GPX root extensions follow tracks, and their children use a foreign namespace.
        append("  <extensions>\n")
        append("    <frem:activityId>${xml(session.id)}</frem:activityId>\n")
        append("    <frem:activityType>${xml(session.type.name)}</frem:activityType>\n")
        append("    <frem:zoneId>${xml(session.zoneId)}</frem:zoneId>\n")
        append(
            "    <frem:recordingSeconds>${decimal(session.durationMillis / 1_000.0)}</frem:recordingSeconds>\n",
        )
        session.end
            ?.takeIf { it >= session.start }
            ?.let {
                append(
                    "    <frem:elapsedSeconds>${decimal((it - session.start) / 1_000.0)}</frem:elapsedSeconds>\n",
                )
            }
        append("  </extensions>\n")
        append("</gpx>\n")
    }

    private fun validCoordinates(point: TrackPoint): Boolean =
        point.latitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() &&
            // GPX's longitudeType has an exclusive upper bound of 180 degrees.
            point.longitude >= -180.0 &&
            point.longitude < 180.0

    // GPX uses xsd:decimal: scientific notation and locale-specific commas are not valid.
    private fun decimal(value: Double): String = BigDecimal.valueOf(value).toPlainString()

    private fun xml(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            // Discard XML 1.0's forbidden controls, FFFE/FFFF and unpaired UTF-16 surrogates.
            when (codePoint) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                '\r'.code -> append("&#13;")
                '\t'.code,
                '\n'.code -> appendCodePoint(codePoint)
                in 0x20..0xD7FF,
                in 0xE000..0xFFFD,
                in 0x10000..0x10FFFF -> appendCodePoint(codePoint)
            }
        }
    }
}
