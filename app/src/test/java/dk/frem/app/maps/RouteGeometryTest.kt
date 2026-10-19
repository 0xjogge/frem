package dk.frem.app.maps

import dk.frem.app.recording.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** Pure JVM regressions: no MapView, native library, network or location provider is needed. */
class RouteGeometryTest {
    @Test
    fun emptyRouteHasNoGeometryOrBounds() = runBlocking {
        val route = buildDisplayRoute(emptyList())
        assertEquals(0, route.pointCount)
        assertEquals(0, route.segmentCount)
        assertNull(route.bounds)
        assertNull(route.first)
        assertNull(route.last)
        assertTrue(route.features.features()!!.isEmpty())
    }

    @Test
    fun singleFixKeepsStartEndAndCurrentMarkersWithoutInventingALine() = runBlocking {
        val route = buildDisplayRoute(listOf(point(0, 12.0)))
        assertEquals(1, route.pointCount)
        assertEquals(1, route.segmentCount)
        assertTrue(route.lines().isEmpty())
        assertEquals(12.0, route.bounds!!.longitude, 1e-9)
        assertEquals(55.0, route.bounds.latitude, 1e-9)
        for (kind in listOf("start", "end", "current")) {
            val marker = route.features.features()!!.single { it.getStringProperty("kind") == kind }
            assertEquals(Point.fromLngLat(12.0, 55.0), marker.geometry())
        }
    }

    @Test
    fun continuousFixesStayInTheirOriginalOrder() = runBlocking {
        val route = buildDisplayRoute(listOf(point(0, 12.0), point(1, 12.002), point(2, 12.001)))
        assertEquals(1, route.segmentCount)
        assertEquals(listOf(12.0, 12.002, 12.001), route.lines().single().map { it.longitude() })
    }

    @Test
    fun pausesMissingFixesSessionsAndClockDiscontinuitiesEachPreventConnections() = runBlocking {
        val first = point(0, 12.0)
        val next = point(1, 13.0)
        val discontinuities =
            mapOf(
                "paused segment" to next.copy(segment = 1),
                "different session" to next.copy(sessionId = "another-recording"),
                "missing sequence" to next.copy(sequence = 2),
                "rebooted monotonic clock" to next.copy(elapsedRealtimeNanos = 1),
                "duplicate monotonic timestamp" to
                    next.copy(elapsedRealtimeNanos = first.elapsedRealtimeNanos),
            )
        for ((reason, afterGap) in discontinuities) {
            val route = buildDisplayRoute(listOf(first, afterGap))
            assertEquals(reason, 2, route.pointCount)
            assertEquals(reason, 2, route.segmentCount)
            assertTrue("Must not connect across $reason", route.lines().isEmpty())
        }
    }

    @Test
    fun stationaryStopAndWallClockChangesDoNotInventSignalGaps() = runBlocking {
        val first = point(0, 12.0)
        val moved =
            point(1, 12.0001)
                .copy(
                    elapsedRealtimeNanos = first.elapsedRealtimeNanos + 120_000_000_000L,
                    recordedAt = first.recordedAt + 120_000,
                )
        val afterClockChange =
            point(2, 12.0002)
                .copy(
                    elapsedRealtimeNanos = moved.elapsedRealtimeNanos + 1_000_000_000L,
                    recordedAt = first.recordedAt - 3_600_000,
                )
        val route = buildDisplayRoute(listOf(first, moved, afterClockChange))
        assertEquals(1, route.segmentCount)
        assertEquals(3, route.lines().single().size)
    }

    @Test
    fun invalidFixesBreakTheLineWithoutRemovingSubsequentValidGeometry() = runBlocking {
        val route =
            buildDisplayRoute(
                listOf(
                    point(0, 12.0),
                    point(1, Double.NaN),
                    point(2, 12.1).copy(latitude = 91.0),
                    point(3, 13.0),
                    point(4, 13.001),
                ),
            )
        assertEquals(3, route.pointCount)
        assertEquals(2, route.segmentCount)
        assertEquals(listOf(13.0, 13.001), route.lines().single().map { it.longitude() })
        assertEquals(Point.fromLngLat(12.0, 55.0), route.first)
    }

    @Test
    fun dateLineCrossingsFitTheShortArcAndCutAtBothWorldEdgesInEitherDirection() = runBlocking {
        for ((start, end) in listOf(179.8 to -179.7, -179.8 to 179.7)) {
            val route = buildDisplayRoute(listOf(point(0, start), point(1, end, latitude = 55.1)))
            assertEquals(0.5, route.bounds!!.longitudeSpan, 1e-9)
            assertTrue(abs(route.bounds.longitude) > 179.0)
            val lines = route.lines()
            assertEquals(2, lines.size)
            assertEquals(start, lines.first().first().longitude(), 0.0)
            assertEquals(end, lines.last().last().longitude(), 0.0)
            assertEquals(180.0, abs(lines.first().last().longitude()), 0.0)
            assertEquals(-lines.first().last().longitude(), lines.last().first().longitude(), 0.0)
            assertEquals(lines.first().last().latitude(), lines.last().first().latitude(), 0.0)
            assertNoWorldSpanningEdges(lines)
        }
    }

    @Test
    fun exactDateLineAliasesNeverProduceNaNOrAnAcrossTheWorldLine() = runBlocking {
        for ((start, end) in listOf(180.0 to -180.0, -180.0 to 180.0)) {
            val route = buildDisplayRoute(listOf(point(0, start), point(1, end, latitude = 55.1)))
            assertEquals(0.0, route.bounds!!.longitudeSpan, 0.0)
            assertNoWorldSpanningEdges(route.lines())
            assertTrue(
                route.lines().flatten().all {
                    it.latitude().isFinite() && it.longitude().isFinite()
                },
            )
        }
    }

    @Test
    fun polarFixesStayRenderableWithoutChangingTheRecordedLatitude() = runBlocking {
        val points = listOf(point(0, 12.0, latitude = 90.0), point(1, 12.001, latitude = -90.0))
        val route = buildDisplayRoute(points)
        assertEquals(90.0, points.first().latitude, 0.0)
        assertEquals(-90.0, points.last().latitude, 0.0)
        assertTrue(route.bounds!!.northY.isFinite())
        assertTrue(route.bounds.southY.isFinite())
        assertTrue(route.lines().flatten().all { abs(it.latitude()) <= MERCATOR_LATITUDE })
    }

    @Test
    fun longRouteSimplificationPreservesTurnsEndpointsAndGapsWithoutMutatingRecordedPoints() =
        runBlocking {
            val points =
                (0 until 10_000).map { index ->
                    val t = (index % 2500) * 0.000001
                    val (longitude, latitude) =
                        when (index / 2500) {
                            0 -> 12.0 + t to 55.0
                            1 -> 12.0025 to 55.0 + t
                            2 -> 12.0025 - t to 55.0025
                            else -> 12.0 to 55.0025 - t
                        }
                    // A pause moves the second segment elsewhere. Simplification must not join it.
                    point(index, longitude + if (index >= 5000) 0.02 else 0.0, latitude)
                        .copy(
                            segment = if (index >= 5000) 1 else 0,
                            altitudeMeters = index.toDouble(),
                        )
                }
            val original = points.toList()
            val route = buildDisplayRoute(points)
            val lines = route.lines()
            val displayed = lines.flatten()
            assertEquals(original, points)
            assertEquals(10_000, route.pointCount)
            assertEquals(2, route.segmentCount)
            assertEquals(2, lines.size)
            assertTrue("Should substantially reduce display work", displayed.size < 1000)
            assertEquals(points[0].coordinate(), lines[0].first())
            assertEquals(points[4999].coordinate(), lines[0].last())
            assertEquals(points[5000].coordinate(), lines[1].first())
            assertEquals(points.last().coordinate(), lines[1].last())
            for (corner in listOf(2500, 7500)) {
                assertTrue(
                    "Corner $corner must remain within 3 metres of a displayed vertex",
                    displayed.any { distanceMeters(it, points[corner]) <= 3.0 },
                )
            }
        }

    private fun point(index: Int, longitude: Double, latitude: Double = 55.0) =
        TrackPoint(
            sessionId = "recording",
            sequence = index.toLong(),
            segment = 0,
            recordedAt = 1_000L + index * 1_000L,
            elapsedRealtimeNanos = 1_000_000_000L + index * 1_000_000_000L,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = 3f,
        )

    private fun TrackPoint.coordinate(): Point = Point.fromLngLat(longitude, latitude)

    private fun DisplayRoute.lines(): List<List<Point>> =
        features.features()!!.flatMap {
            (it.geometry() as? MultiLineString)?.coordinates().orEmpty()
        }

    private fun assertNoWorldSpanningEdges(lines: List<List<Point>>) {
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertTrue(line.size >= 2)
            line.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    "A line must not cross the whole world",
                    abs(a.longitude() - b.longitude()) <= 180.0,
                )
            }
        }
    }

    private fun distanceMeters(point: Point, target: TrackPoint): Double =
        hypot(
            (point.longitude() - target.longitude) * 111_320.0 * cos(target.latitude * PI / 180.0),
            (point.latitude() - target.latitude) * 111_320.0,
        )
}
