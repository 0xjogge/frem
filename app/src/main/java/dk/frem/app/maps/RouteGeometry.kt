package dk.frem.app.maps

import dk.frem.app.recording.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

internal const val MERCATOR_LATITUDE = 85.0511287798066
private const val EARTH_CIRCUMFERENCE = 40_075_016.686

internal data class DisplayRoute(
    val features: FeatureCollection,
    val first: Point? = null,
    val last: Point? = null,
    val sessionId: String? = null,
    val pointCount: Int = 0,
    val segmentCount: Int = 0,
    val bounds: RouteBounds? = null,
) {
    companion object {
        val Empty = DisplayRoute(FeatureCollection.fromFeatures(emptyList()))
    }
}

/** Smallest longitude arc, including routes which cross the anti-meridian. */
internal data class RouteBounds(
    val west: Double,
    val longitudeSpan: Double,
    val northY: Double,
    val southY: Double,
) {
    val longitude
        get() = wrapLongitude(west + longitudeSpan / 2.0)

    val latitude
        get() = latitudeFromMercator((northY + southY) / 2.0)
}

/** Display-only processing. Nothing is written back to the recorded points or statistics. */
internal suspend fun buildDisplayRoute(points: List<TrackPoint>): DisplayRoute {
    if (points.isEmpty()) return DisplayRoute.Empty
    val context = currentCoroutineContext()
    val runs = mutableListOf<MutableList<Point>>()
    val longitudes = ArrayList<Double>(points.size)
    var previous: TrackPoint? = null
    var first: Point? = null
    var last: Point? = null
    var session: String? = null
    var north = 1.0
    var south = 0.0
    var count = 0
    for ((index, sample) in points.withIndex()) {
        if (index % 1024 == 0) context.ensureActive()
        if (
            !sample.latitude.isFinite() ||
                !sample.longitude.isFinite() ||
                sample.latitude !in -90.0..90.0 ||
                sample.longitude !in -180.0..180.0
        ) {
            previous = null
            continue
        }
        val point =
            Point.fromLngLat(
                sample.longitude,
                sample.latitude.coerceIn(-MERCATOR_LATITUDE, MERCATOR_LATITUDE),
            )
        val before = previous
        val elapsed =
            if (before == null) 0L else sample.elapsedRealtimeNanos - before.elapsedRealtimeNanos
        // The recorder persists real signal gaps and pauses as explicit segments. Retained
        // points can be far apart in time after stationary fixes were filtered, so time gaps
        // and wall-clock adjustments alone must not split an otherwise continuous route.
        val connected =
            before != null &&
                before.sessionId == sample.sessionId &&
                before.segment == sample.segment &&
                sample.sequence == before.sequence + 1L &&
                elapsed > 0
        if (!connected) runs.add(mutableListOf())
        runs.last().add(point)
        if (first == null) {
            first = point
            session = sample.sessionId
        }
        last = point
        longitudes.add((point.longitude() + 360.0) % 360.0)
        val y = mercatorY(point.latitude())
        north = min(north, y)
        south = max(south, y)
        count++
        previous = sample
    }
    if (first == null) return DisplayRoute.Empty

    // Chunked Douglas–Peucker keeps turns and endpoints, and bounds worst-case CPU on
    // noisy, very long recordings. There is no uniform "every nth point" corner cutting.
    var tolerance = if (count > 2_000) 3.0 else 0.0
    var simplified = runs.map { simplifyRun(it, tolerance) }
    while (simplified.sumOf { it.size } > 16_000 && tolerance < 96.0) {
        context.ensureActive()
        tolerance = max(3.0, tolerance * 2.0)
        simplified = runs.map { simplifyRun(it, tolerance) }
    }
    val features = mutableListOf<Feature>()
    val lines = mutableListOf<List<Point>>()
    for ((index, run) in simplified.withIndex()) {
        if (index % 64 == 0) context.ensureActive()
        if (run.size == 1) features.add(feature(run.single(), "isolated"))
        else lines.addAll(splitAtDateLine(run))
    }
    if (lines.isNotEmpty()) features.add(feature(MultiLineString.fromLngLats(lines), "route"))
    features.add(feature(first, "start"))
    features.add(feature(checkNotNull(last), "end"))
    // The current/last-fix layer is toggled by live mode, without rebuilding this collection.
    features.add(feature(checkNotNull(last), "current"))

    longitudes.sort()
    var gap = -1.0
    var west = longitudes.first()
    for (i in longitudes.indices) {
        val next = if (i == longitudes.lastIndex) longitudes.first() + 360.0 else longitudes[i + 1]
        val distance = next - longitudes[i]
        if (distance > gap) {
            gap = distance
            west = next % 360.0
        }
    }
    return DisplayRoute(
        FeatureCollection.fromFeatures(features),
        first,
        last,
        session,
        count,
        runs.size,
        RouteBounds(west, (360.0 - gap).coerceAtLeast(0.0), north, south),
    )
}

private fun feature(geometry: org.maplibre.geojson.Geometry, kind: String): Feature =
    Feature.fromGeometry(geometry).apply { addStringProperty("kind", kind) }

private suspend fun simplifyRun(points: List<Point>, meters: Double): List<Point> {
    if (meters == 0.0 || points.size < 3) return points
    val output = ArrayList<Point>()
    var start = 0
    while (start < points.lastIndex) {
        currentCoroutineContext().ensureActive()
        val end = min(start + 512, points.lastIndex)
        val x = DoubleArray(end - start + 1)
        val y = DoubleArray(x.size)
        val latitude = points[start].latitude()
        val scale = EARTH_CIRCUMFERENCE * cos(latitude * PI / 180.0).coerceAtLeast(0.01)
        var longitude = points[start].longitude()
        x[0] = longitude / 360.0 * scale
        y[0] = mercatorY(latitude) * scale
        for (i in 1 until x.size) {
            val current = points[start + i]
            longitude += wrapLongitude(current.longitude() - points[start + i - 1].longitude())
            x[i] = longitude / 360.0 * scale
            y[i] = mercatorY(current.latitude()) * scale
        }
        val keep = BooleanArray(x.size)
        keep[0] = true
        keep[keep.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to x.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var furthest = -1
            var distance = meters * meters
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val length = dx * dx + dy * dy
            for (i in a + 1 until b) {
                val t =
                    if (length == 0.0) 0.0
                    else (((x[i] - x[a]) * dx + (y[i] - y[a]) * dy) / length).coerceIn(0.0, 1.0)
                val ex = x[i] - x[a] - t * dx
                val ey = y[i] - y[a] - t * dy
                val squared = ex * ex + ey * ey
                if (squared > distance) {
                    distance = squared
                    furthest = i
                }
            }
            if (furthest >= 0) {
                keep[furthest] = true
                stack.add(a to furthest)
                stack.add(furthest to b)
            }
        }
        for (i in keep.indices) {
            if (keep[i] && (start == 0 || i != 0)) output.add(points[start + i])
        }
        start = end
    }
    return output
}

/** RFC 7946 anti-meridian cutting, so a short crossing never draws across the whole world. */
private fun splitAtDateLine(points: List<Point>): List<List<Point>> {
    val result = mutableListOf<List<Point>>()
    var line = mutableListOf(points.first())
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val delta = b.longitude() - a.longitude()
        if (abs(delta) > 180.0) {
            val adjusted = b.longitude() + if (delta > 0) -360.0 else 360.0
            // Choose from the original crossing direction. With exact +180/-180 aliases,
            // adjusted == a.longitude(), so comparing those values picks the wrong edge.
            val edge = if (delta > 0.0) -180.0 else 180.0
            val denominator = adjusted - a.longitude()
            val t = if (abs(denominator) < 1e-12) 0.0 else (edge - a.longitude()) / denominator
            val latitude = a.latitude() + (b.latitude() - a.latitude()) * t
            line.add(Point.fromLngLat(edge, latitude))
            if (line.size >= 2) result.add(line)
            line = mutableListOf(Point.fromLngLat(-edge, latitude))
        }
        line.add(b)
    }
    if (line.size >= 2) result.add(line)
    return result
}

internal fun mercatorY(latitude: Double): Double {
    val sine = sin(latitude.coerceIn(-MERCATOR_LATITUDE, MERCATOR_LATITUDE) * PI / 180.0)
    return (0.5 - ln((1.0 + sine) / (1.0 - sine)) / (4.0 * PI)).coerceIn(0.0, 1.0)
}

internal fun latitudeFromMercator(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

internal fun wrapLongitude(longitude: Double): Double =
    ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
