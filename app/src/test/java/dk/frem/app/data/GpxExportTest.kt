package dk.frem.app.data

import dk.frem.app.recording.ActivitySession
import dk.frem.app.recording.ActivityType
import dk.frem.app.recording.RecordingState
import dk.frem.app.recording.TrackPoint
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class GpxExportTest {
    @Test
    fun exportsGpxNamespaceEscapedUnicodeTitleAndStableRootMetadata() {
        val title = "Sø & <skov> \"🌲\" 'tur'\t\n\r"
        val session =
            session()
                .copy(
                    title = title,
                    id = "stable<&\"'id>",
                    type = ActivityType.TRAIL_RUNNING,
                )
        val root = parse(GpxExport.create(session, emptyList()))

        assertEquals("gpx", root.localName)
        assertEquals(GPX, root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("Frem", root.getAttribute("creator"))
        assertEquals("UTF-8", root.ownerDocument.xmlEncoding)
        assertEquals(title, root.elements("name").single().textContent)
        assertEquals("TRAIL_RUNNING", root.elements("type").single().textContent)
        assertEquals(
            START,
            root.elements("metadata").single().elements("time").single().textContent,
        )
        val extensions = root.elements("extensions").single()
        assertSame(root, extensions.parentNode)
        assertEquals(session.id, extensions.elements("activityId", FREM).single().textContent)
        assertEquals(
            "TRAIL_RUNNING",
            extensions.elements("activityType", FREM).single().textContent,
        )
        assertEquals("Europe/Copenhagen", extensions.elements("zoneId", FREM).single().textContent)
        assertEquals("60.25", extensions.elements("recordingSeconds", FREM).single().textContent)
        assertEquals("125.75", extensions.elements("elapsedSeconds", FREM).single().textContent)
        val children =
            (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
        assertEquals(listOf("metadata", "trk", "extensions"), children.map { it.localName })
    }

    @Test
    fun removesIllegalXmlCharactersAndUnpairedSurrogatesButKeepsValidCodePoints() {
        val valid = "Æø漢字🌲\t\n\r\uD7FF\uE000\uFFFD" + String(Character.toChars(0x10FFFF))
        val invalid = "\u0000\u0001\u0008\u000B\u000C\u001F\uFFFE\uFFFF\uD800|\uDC00"
        val session = session().copy(title = "$valid$invalid<end>")

        val root = parse(GpxExport.create(session, emptyList()))

        assertEquals("$valid|<end>", root.elements("name").single().textContent)
    }

    @Test
    fun sortsByRecordingSequenceAndKeepsEveryPauseSegmentAndOriginalUtcTimestamp() {
        val session = session()
        val points =
            listOf(
                point(0, segment = 0),
                point(1, segment = 0),
                point(2, segment = 3),
                point(3, segment = 3),
                point(4, segment = 8),
            )
        val root = parse(GpxExport.create(session, points.reversed()))

        val segments = root.elements("trkseg")
        assertEquals(listOf(2, 2, 1), segments.map { it.elements("trkpt").size })
        val exported = root.elements("trkpt")
        assertEquals(points.map { it.latitude }, exported.map { it.getAttribute("lat").toDouble() })
        assertEquals(
            points.map { it.longitude },
            exported.map { it.getAttribute("lon").toDouble() },
        )
        assertEquals(
            points.map { Instant.ofEpochMilli(it.recordedAt).toString() },
            exported.map { it.elements("time").single().textContent },
        )
        assertEquals(
            "2026-10-18T23:30:01.125Z",
            exported.first().elements("time").single().textContent,
        )
    }

    @Test
    fun repeatedSegmentNumberAfterAnotherSegmentDoesNotReconnectEarlierPoints() {
        val root =
            parse(
                GpxExport.create(
                    session(),
                    listOf(point(2, segment = 4), point(0, segment = 4), point(1, segment = 5)),
                ),
            )

        assertEquals(listOf(1, 1, 1), root.elements("trkseg").map { it.elements("trkpt").size })
    }

    @Test
    fun excludesForeignSessionsIncludingStalePointsWithOverlappingSequences() {
        val own = point(0)
        val foreign = point(0).copy(sessionId = "previous-session", latitude = 1.0, longitude = 2.0)
        val root =
            parse(
                GpxExport.create(
                    session(),
                    listOf(foreign, own, foreign.copy(sequence = 1, segment = 1)),
                ),
            )

        val exported = root.elements("trkpt").single()
        assertEquals(own.latitude, exported.getAttribute("lat").toDouble(), 0.0)
        assertEquals(own.longitude, exported.getAttribute("lon").toDouble(), 0.0)
        assertEquals(1, root.elements("trkseg").size)
    }

    @Test
    fun rejectsNonfiniteAndOutOfBoundsCoordinatesWithoutBridgingRejectedPoints() {
        val invalidCoordinates =
            listOf(
                Double.NaN to 0.0,
                Double.POSITIVE_INFINITY to 0.0,
                Double.NEGATIVE_INFINITY to 0.0,
                -90.00001 to 0.0,
                90.00001 to 0.0,
                0.0 to Double.NaN,
                0.0 to Double.POSITIVE_INFINITY,
                0.0 to Double.NEGATIVE_INFINITY,
                0.0 to -180.00001,
                0.0 to 180.0,
                0.0 to 180.00001,
            )
        val first = point(0).copy(latitude = -90.0, longitude = -180.0)
        val last = point(invalidCoordinates.size + 1L).copy(latitude = 90.0, longitude = 179.99999)
        val invalid = invalidCoordinates.mapIndexed { index, (latitude, longitude) ->
            point(index + 1L).copy(latitude = latitude, longitude = longitude)
        }
        val root = parse(GpxExport.create(session(), listOf(first) + invalid + last))

        assertEquals(listOf(1, 1), root.elements("trkseg").map { it.elements("trkpt").size })
        assertEquals(listOf("-90.0", "90.0"), root.elements("trkpt").map { it.getAttribute("lat") })
        assertEquals(
            listOf("-180.0", "179.99999"),
            root.elements("trkpt").map { it.getAttribute("lon") },
        )
    }

    @Test
    fun onlyFiniteElevationsAreIncludedAndUnknownElevationIsNotReplacedWithZero() {
        val altitudes =
            listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -12.5, 0.0)
        val points = altitudes.mapIndexed { index, altitude ->
            point(index.toLong()).copy(altitudeMeters = altitude)
        }
        val root = parse(GpxExport.create(session(), points))

        assertEquals(altitudes.size, root.elements("trkpt").size)
        assertEquals(listOf("-12.5", "0.0"), root.elements("ele").map { it.textContent })
        root.elements("trkpt").take(4).forEach { assertTrue(it.elements("ele").isEmpty()) }
        val withElevation = root.elements("trkpt")[4]
        val children =
            (0 until withElevation.childNodes.length).mapNotNull {
                withElevation.childNodes.item(it) as? Element
            }
        assertEquals(listOf("ele", "time"), children.map { it.localName })
    }

    @Test
    fun decimalCoordinatesAndElevationAreLocaleIndependentAndNeverScientificNotation() {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val point = point(0).copy(latitude = 1e-7, longitude = -1e-8, altitudeMeters = 1e20)
            val root = parse(GpxExport.create(session(), listOf(point)))

            val exported = root.elements("trkpt").single()
            assertDecimal("0.0000001", exported.getAttribute("lat"))
            assertDecimal("-0.00000001", exported.getAttribute("lon"))
            assertDecimal("100000000000000000000", exported.elements("ele").single().textContent)
            assertEquals("2026-10-18T23:30:01.125Z", exported.elements("time").single().textContent)
            assertEquals(
                START,
                root.elements("metadata").single().elements("time").single().textContent,
            )
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun emptyOrFullyFilteredTrackHasMetadataAndNoEmptySegmentsOrInventedPositions() {
        val inputs =
            listOf(
                emptyList(),
                listOf(point(0).copy(sessionId = "stale-session")),
                listOf(point(0).copy(latitude = Double.NaN)),
            )
        for (points in inputs) {
            val root = parse(GpxExport.create(session(), points))
            assertEquals(1, root.elements("trk").size)
            assertTrue(root.elements("trkseg").isEmpty())
            assertTrue(root.elements("trkpt").isEmpty())
            assertTrue(root.elements("wpt").isEmpty())
            assertTrue(root.elements("rte").isEmpty())
            assertEquals(
                START,
                root.elements("metadata").single().elements("time").single().textContent,
            )
        }
    }

    @Test
    fun singlePointIsAValidSegmentAndMissingEndDoesNotInventElapsedTime() {
        val root = parse(GpxExport.create(session().copy(end = null), listOf(point(0))))

        assertEquals(1, root.elements("trkseg").size)
        assertEquals(1, root.elements("trkpt").size)
        assertTrue(root.elements("elapsedSeconds", FREM).isEmpty())
        assertEquals("60.25", root.elements("recordingSeconds", FREM).single().textContent)
    }

    private fun session(): ActivitySession =
        ActivitySession(
            id = SESSION_ID,
            title = "Evening walk",
            type = ActivityType.WALKING,
            start = Instant.parse(START).toEpochMilli(),
            end = Instant.parse(START).toEpochMilli() + 125_750,
            zoneId = "Europe/Copenhagen",
            state = RecordingState.FINISHED,
            durationMillis = 60_250,
        )

    private fun point(sequence: Long, segment: Int = 0): TrackPoint =
        TrackPoint(
            sessionId = SESSION_ID,
            sequence = sequence,
            segment = segment,
            recordedAt = Instant.parse(START).toEpochMilli() + (sequence + 1) * 1_000,
            elapsedRealtimeNanos = (sequence + 1) * 1_000_000_000,
            latitude = 55.0 + sequence / 1_000.0,
            longitude = 12.0 + sequence / 1_000.0,
            accuracyMeters = 5f,
        )

    private fun parse(xml: String): Element {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        return xml.byteInputStream(Charsets.UTF_8).use {
            factory.newDocumentBuilder().parse(it).documentElement
        }
    }

    private fun Element.elements(name: String, namespace: String = GPX): List<Element> {
        val matches = getElementsByTagNameNS(namespace, name)
        return (0 until matches.length).map { matches.item(it) as Element }
    }

    private fun assertDecimal(expected: String, actual: String) {
        assertTrue("Invalid GPX decimal: $actual", actual.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?")))
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)))
    }

    private companion object {
        const val GPX = "http://www.topografix.com/GPX/1/1"
        const val FREM = "urn:dk.frem:gpx:1"
        const val SESSION_ID = "f36d5a82-3a86-4988-9e3b-d315302eeb04"
        const val START = "2026-10-18T23:30:00.125Z"
    }
}
