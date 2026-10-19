package dk.frem.app.recording

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** An Android-independent, unmodified observation from a location provider. */
data class RecordingFix(
    val recordedAt: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
)

internal data class TrackUpdate(
    val point: TrackPoint?,
    val distanceMeters: Double,
    val ascentMeters: Double?,
    val descentMeters: Double?,
    val speedMetersPerSecond: Float?,
    val fix: RecordingFix,
)

/** Keeps sampled curves; no map/display simplification is applied to the recorded track. */
internal class TrackProcessor(
    private val sessionId: String,
    var type: ActivityType,
    private val startedAtNanos: Long,
    private var sequence: Long = 0,
    private var segment: Int = 0,
    initialDistanceMeters: Double = 0.0,
    initialAscentMeters: Double? = null,
    initialDescentMeters: Double? = null,
) {
    var distanceMeters: Double = initialDistanceMeters
        private set

    var ascentMeters: Double? = initialAscentMeters
        private set

    var descentMeters: Double? = initialDescentMeters
        private set

    private var anchor: RecordingFix? = null
    private var previousSignal: RecordingFix? = null
    private var movementEstablished = false
    private var lastSeenNanos = Long.MIN_VALUE
    private var smoothedSpeed: Double? = null
    private var elevationReference: Double? = null
    private var previousElevation: Pair<Long, Double>? = null
    private val elevationWindow = ArrayDeque<Double>()

    fun accept(fix: RecordingFix, nowNanos: Long): TrackUpdate? {
        val elapsed = fix.elapsedRealtimeNanos
        if (
            elapsed < startedAtNanos ||
                elapsed <= lastSeenNanos ||
                elapsed > nowNanos ||
                nowNanos - elapsed > MAX_FIX_AGE_NANOS ||
                !fix.latitude.isFinite() ||
                fix.latitude !in -90.0..90.0 ||
                !fix.longitude.isFinite() ||
                fix.longitude !in -180.0..180.0 ||
                !fix.accuracyMeters.isFinite() ||
                fix.accuracyMeters !in 0f..MAX_ACCURACY_METERS
        )
            return null
        lastSeenNanos = elapsed
        val previous = previousSignal
        val oldAnchor = anchor
        val signalGap =
            previous != null && elapsed - previous.elapsedRealtimeNanos > SIGNAL_GAP_NANOS
        val reportedSpeed =
            fix.speedMetersPerSecond?.toDouble()?.takeIf {
                it.isFinite() &&
                    it >= 0 &&
                    fix.speedAccuracyMetersPerSecond?.let { accuracy ->
                        accuracy.isFinite() && accuracy in 0f..1.5f
                    } == true
            }
        val maximumSpeed =
            if (type.cycling) 45.0
            else
                when (type) {
                    ActivityType.WALKING,
                    ActivityType.HIKING -> 7.0
                    else -> 15.0
                }
        // The first fix establishes an origin. A provider's initial velocity is not a journey.
        if (oldAnchor != null && !signalGap) {
            val dt = (elapsed - oldAnchor.elapsedRealtimeNanos) / 1e9
            val displacement = distance(oldAnchor, fix)
            val uncertainty = (oldAnchor.accuracyMeters + fix.accuracyMeters).toDouble()
            val speedLimit = minOf(maximumSpeed, (reportedSpeed ?: maximumSpeed) + 5.0)
            if (
                displacement > speedLimit * dt + uncertainty ||
                    reportedSpeed != null && reportedSpeed > maximumSpeed
            )
                return null
        }

        val stationary = reportedSpeed != null && reportedSpeed < 0.4
        // Correct an uncertain origin only during acquisition or while verified stationary.
        // Improved precision during an established run must not discard genuine movement.
        val precisionCorrection =
            oldAnchor != null &&
                (stationary || !movementEstablished && reportedSpeed == null) &&
                fix.accuracyMeters * 2 < oldAnchor.accuracyMeters &&
                distance(oldAnchor, fix) <= oldAnchor.accuracyMeters + fix.accuracyMeters
        val driftCorrection =
            stationary &&
                oldAnchor != null &&
                distance(oldAnchor, fix) >
                    maxOf(10.0, (oldAnchor.accuracyMeters + fix.accuracyMeters).toDouble())
        if (signalGap || precisionCorrection || driftCorrection) {
            segment++
            anchor = null
            smoothedSpeed = null
            elevationReference = null
            previousElevation = null
            elevationWindow.clear()
            movementEstablished = false
        }
        val currentAnchor = anchor
        val movement = currentAnchor?.let { distance(it, fix) } ?: 0.0
        val threshold =
            currentAnchor?.let {
                val accuracy = minOf(it.accuracyMeters, fix.accuracyMeters)
                // Without a trustworthy Doppler velocity, movement must clear the uncertainty
                // envelope. Displacement accumulates from the anchor, so slow walks still count.
                if (reportedSpeed == null) maxOf(2.0, accuracy * 1.5)
                else maxOf(1.2, accuracy * 0.4)
            } ?: 0.0
        val moved = currentAnchor == null || movement >= threshold && !stationary
        val dt = previous?.let { (elapsed - it.elapsedRealtimeNanos) / 1e9 } ?: 0.0
        val rawSpeed =
            if (previous == null || signalGap || precisionCorrection || driftCorrection) null
            else
                reportedSpeed?.coerceAtMost(maximumSpeed)
                    ?: currentAnchor?.let {
                        val anchorAge = (elapsed - it.elapsedRealtimeNanos) / 1e9
                        if (moved) (movement / anchorAge).coerceAtMost(maximumSpeed)
                        else if (anchorAge >= 5.0) 0.0 else null
                    }
        if (rawSpeed != null) {
            val alpha = 1 - exp(-dt.coerceIn(0.0, 5.0) / 3.0)
            smoothedSpeed = smoothedSpeed?.let { it + alpha * (rawSpeed - it) } ?: rawSpeed
        }
        previousSignal = fix
        val altitude = reliableAltitude(fix)
        if (moved)
            updateElevation(
                fix.elapsedRealtimeNanos,
                altitude,
                maxOf(3.0, (fix.verticalAccuracyMeters ?: 10f) * 2.0),
            )
        val point =
            if (moved) {
                if (currentAnchor != null) {
                    distanceMeters += movement
                    movementEstablished = true
                }
                anchor = fix
                TrackPoint(
                    sessionId = sessionId,
                    sequence = sequence++,
                    segment = segment,
                    recordedAt = fix.recordedAt,
                    elapsedRealtimeNanos = elapsed,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyMeters = fix.accuracyMeters,
                    altitudeMeters = altitude,
                    speedMetersPerSecond = smoothedSpeed?.toFloat(),
                )
            } else null
        return TrackUpdate(
            point,
            distanceMeters,
            ascentMeters,
            descentMeters,
            smoothedSpeed?.toFloat(),
            fix,
        )
    }

    private fun reliableAltitude(fix: RecordingFix): Double? =
        fix.altitudeMeters?.takeIf {
            it.isFinite() &&
                it in -500.0..10_000.0 &&
                fix.verticalAccuracyMeters?.let { accuracy ->
                    accuracy.isFinite() && accuracy in 0f..10f
                } == true
        }

    private fun updateElevation(elapsed: Long, altitude: Double?, threshold: Double) {
        if (altitude == null) return
        val previous = previousElevation
        if (
            previous != null &&
                kotlin.math.abs(altitude - previous.second) >
                    5.0 * (elapsed - previous.first) / 1e9 + 3.0
        )
            return
        previousElevation = elapsed to altitude
        elevationWindow.addLast(altitude)
        if (elevationWindow.size > 3) elevationWindow.removeFirst()
        val filtered = elevationWindow.sorted()[elevationWindow.size / 2]
        if (ascentMeters == null) ascentMeters = 0.0
        if (descentMeters == null) descentMeters = 0.0
        val reference = elevationReference
        if (reference == null) elevationReference = filtered
        else if (filtered - reference >= threshold) {
            ascentMeters = (ascentMeters ?: 0.0) + filtered - reference
            elevationReference = filtered
        } else if (reference - filtered >= threshold) {
            descentMeters = (descentMeters ?: 0.0) + reference - filtered
            elevationReference = filtered
        }
    }

    companion object {
        const val SIGNAL_GAP_NANOS = 30_000_000_000L
        private const val MAX_FIX_AGE_NANOS = 10_000_000_000L
        private const val MAX_ACCURACY_METERS = 35f

        private fun distance(a: RecordingFix, b: RecordingFix): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h =
                sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(a.latitude)) *
                        cos(Math.toRadians(b.latitude)) *
                        sin(dLon / 2).pow(2)
            return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
