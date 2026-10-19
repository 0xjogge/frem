package dk.frem.app.recording

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class TrackProcessorTest {
    @Test
    fun initialFixEstablishesOriginWithoutDistanceOrAnInventedSpeed() {
        val track = processor()
        val first = accept(track, fix(1, 1_000.0, speed = 200f))!!
        assertEquals(0.0, first.distanceMeters, 0.0)
        assertNull(first.speedMetersPerSecond)
        assertEquals(0L, first.point!!.sequence)
    }

    @Test
    fun staleFutureDuplicateAndOutOfOrderFixesCannotChangeTrack() {
        val track = processor()
        assertNull(track.accept(fix(1, 0.0), nanos(12)))
        assertNull(track.accept(fix(13, 0.0), nanos(12)))
        assertNotNull(accept(track, fix(12, 0.0)))
        assertNull(accept(track, fix(12, 4.0)))
        assertNull(accept(track, fix(11, 4.0)))
        assertEquals(3.0, accept(track, fix(13, 3.0))!!.distanceMeters, 0.01)
    }

    @Test
    fun fixesFromBeforeResumeAreNotPartOfTheNewSegment() {
        val track = TrackProcessor("test", ActivityType.RUNNING, startedAtNanos = nanos(10))
        assertNull(track.accept(fix(9, 0.0), nanos(10)))
        assertNotNull(track.accept(fix(10, 0.0), nanos(10)))
    }

    @Test
    fun poorAccuracyAndInvalidCoordinatesAreRejected() {
        val track = processor()
        assertNull(accept(track, fix(1, 0.0).copy(accuracyMeters = 100f)))
        assertNull(accept(track, fix(2, 0.0).copy(latitude = Double.NaN)))
        assertNull(accept(track, fix(3, 0.0).copy(longitude = 181.0)))
        assertNull(accept(track, fix(4, 0.0).copy(accuracyMeters = -1f)))
        assertEquals(0L, accept(track, fix(5, 0.0))!!.point!!.sequence)
    }

    @Test
    fun isolatedTeleportIsRejectedWithoutPoisoningTheNextFix() {
        val track = processor()
        accept(track, fix(1, 0.0))
        assertNull(accept(track, fix(2, 1_000.0)))
        val next = accept(track, fix(3, 4.0))!!
        assertEquals(4.0, next.distanceMeters, 0.01)
        assertEquals(1L, next.point!!.sequence)
    }

    @Test
    fun stationaryDriftDoesNotAccumulateDistanceOrExtraPoints() {
        val track = processor()
        accept(track, fix(1, 0.0, speed = 0f))
        for (second in 2L..120L) {
            val update = accept(track, fix(second, sin(second.toDouble()) * 4, speed = 0f))!!
            assertNull(update.point)
            assertEquals(0.0, update.distanceMeters, 0.0)
        }
        val moving = accept(track, fix(121, 3.0, speed = 2f))!!
        assertEquals(0, moving.point!!.segment)
        assertEquals(3.0, moving.distanceMeters, 0.01)
    }

    @Test
    fun missingProviderSpeedFiltersSmallDriftButStillCountsASlowWalk() {
        val stationary = processor()
        accept(stationary, fix(1, 0.0).copy(accuracyMeters = 3f))
        for (second in 2L..60L) {
            val update =
                accept(
                    stationary,
                    fix(second, sin(second.toDouble()) * 2).copy(accuracyMeters = 3f),
                )!!
            assertNull(update.point)
            assertEquals(0.0, update.distanceMeters, 0.0)
        }
        val walking = processor(ActivityType.WALKING)
        accept(walking, fix(1, 0.0).copy(accuracyMeters = 3f))
        for (second in 2L..21L) {
            accept(walking, fix(second, second - 1.0).copy(accuracyMeters = 3f))
        }
        assertEquals(20.0, walking.distanceMeters, 0.01)
    }

    @Test
    fun stationaryOriginCorrectionNeverAddsDistance() {
        val track = processor()
        accept(track, fix(1, 0.0, speed = 0f))
        val correction = accept(track, fix(10, 15.0, speed = 0f))!!
        assertEquals(0.0, correction.distanceMeters, 0.0)
        assertEquals(1, correction.point!!.segment)
        val moving = accept(track, fix(11, 18.0, speed = 3f))!!
        assertEquals(3.0, moving.distanceMeters, 0.01)
    }

    @Test
    fun prolongedSignalLossSplitsTheTrackAndDoesNotCountTheMissingRoute() {
        val track = processor()
        accept(track, fix(1, 0.0))
        accept(track, fix(2, 3.0))
        val returned = accept(track, fix(40, 10_000.0))!!
        assertEquals(1, returned.point!!.segment)
        assertEquals(3.0, returned.distanceMeters, 0.01)
        assertNull(returned.speedMetersPerSecond)
        assertEquals(6.0, accept(track, fix(41, 10_003.0))!!.distanceMeters, 0.01)
    }

    @Test
    fun recoveryResumeKeepsTotalsAndStartsAnIndependentSegment() {
        val track =
            TrackProcessor(
                "saved",
                ActivityType.RUNNING,
                nanos(200),
                sequence = 51,
                segment = 4,
                initialDistanceMeters = 900.0,
                initialAscentMeters = 20.0,
            )
        val first = accept(track, fix(200, 50_000.0))!!
        assertEquals(900.0, first.distanceMeters, 0.0)
        assertEquals(20.0, first.ascentMeters!!, 0.0)
        assertEquals(51L, first.point!!.sequence)
        assertEquals(4, first.point.segment)
        assertEquals(903.0, accept(track, fix(201, 50_003.0))!!.distanceMeters, 0.01)
    }

    @Test
    fun accuratelySampledCurvesKeepTheirLengthAndVertices() {
        val track = processor(ActivityType.CYCLING)
        val radius = 20.0
        val updates =
            (0..36).map { index ->
                val angle = index * PI / 18
                val observation =
                    fix(index + 1L, radius * sin(angle))
                        .copy(
                            longitude = metersToDegrees(radius * cos(angle)),
                            accuracyMeters = 1f,
                        )
                accept(track, observation)!!
            }
        assertEquals(37, updates.count { it.point != null })
        assertEquals(2 * PI * radius, updates.last().distanceMeters, 0.3)
    }

    @Test
    fun sixSecondBatchRetainsSampleTimesCurvesAndSampledSpeed() {
        val track = processor(ActivityType.CYCLING)
        val updates =
            (0..6).map { index ->
                val angle = index * PI / 6
                val sample =
                    fix(index + 1L, 20 * sin(angle))
                        .copy(longitude = metersToDegrees(20 * cos(angle)))
                track.accept(sample, nanos(7))!!
            }
        assertEquals(7, updates.count { it.point != null })
        assertEquals(nanos(1), updates.first().point!!.elapsedRealtimeNanos)
        assertEquals(nanos(7), updates.last().point!!.elapsedRealtimeNanos)
        val chordLength = 40 * sin(PI / 12)
        assertEquals(chordLength * 6, updates.last().distanceMeters, 0.01)
        assertEquals(chordLength.toFloat(), updates.last().speedMetersPerSecond!!, 0.01f)
        assertEquals(0, updates.last().point!!.segment)
    }

    @Test
    fun speedIsSmoothedAndWallClockChangesDoNotAffectItOrDistance() {
        val track = processor()
        accept(track, fix(1, 0.0))
        val slow = accept(track, fix(2, 2.0, speed = 2f))!!
        val faster = accept(track, fix(3, 8.0, speed = 6f).copy(recordedAt = -100_000))!!
        assertEquals(2f, slow.speedMetersPerSecond!!, 0.01f)
        assertTrue(faster.speedMetersPerSecond!! in 2f..4f)
        assertEquals(8.0, faster.distanceMeters, 0.01)
    }

    @Test
    fun improvedInitialAccuracyDoesNotCreateAnArtificialSprint() {
        val track = processor()
        accept(track, fix(1, 0.0).copy(accuracyMeters = 25f))
        val corrected = accept(track, fix(2, 20.0).copy(accuracyMeters = 3f))!!
        assertEquals(0.0, corrected.distanceMeters, 0.0)
        assertNull(corrected.speedMetersPerSecond)
        assertEquals(1, corrected.point!!.segment)
    }

    @Test
    fun improvingAccuracyDuringAnEstablishedRunKeepsTheMovementAndSegment() {
        val track = processor()
        accept(track, fix(1, 0.0, speed = 5f).copy(accuracyMeters = 10f))
        accept(track, fix(3, 10.0, speed = 5f).copy(accuracyMeters = 10f))
        val improved = accept(track, fix(5, 20.0, speed = 5f).copy(accuracyMeters = 3f))!!
        assertEquals(20.0, improved.distanceMeters, 0.01)
        assertEquals(0, improved.point!!.segment)
        assertEquals(2L, improved.point.sequence)
    }

    @Test
    fun reliableMovementIsNotMistakenForAnInitialPrecisionCorrection() {
        val track = processor()
        accept(track, fix(1, 0.0).copy(accuracyMeters = 25f))
        val moving = accept(track, fix(3, 10.0, speed = 5f).copy(accuracyMeters = 3f))!!
        assertEquals(10.0, moving.distanceMeters, 0.01)
        assertEquals(0, moving.point!!.segment)
    }

    @Test
    fun altitudeNeedsVerticalAccuracyAndNoiseDoesNotBecomeAscent() {
        val withoutAltitude = processor()
        accept(withoutAltitude, fix(1, 0.0).copy(altitudeMeters = 100.0))
        assertNull(withoutAltitude.ascentMeters)
        val flat = processor()
        for (second in 1L..30L) {
            accept(
                flat,
                fix(second, second * 3.0)
                    .copy(
                        altitudeMeters = 100.0 + sin(second.toDouble()) * 1.5,
                        verticalAccuracyMeters = 3f,
                    ),
            )
        }
        assertEquals(0.0, flat.ascentMeters!!, 0.0)
        val climbing = processor()
        for (second in 1L..11L) {
            accept(
                climbing,
                fix(second, second * 3.0)
                    .copy(
                        altitudeMeters = 100.0 + second * 2,
                        verticalAccuracyMeters = 3f,
                    ),
            )
        }
        assertTrue(climbing.ascentMeters!! in 16.0..20.0)
    }

    private fun processor(type: ActivityType = ActivityType.RUNNING) =
        TrackProcessor("test", type, 0)

    private fun accept(track: TrackProcessor, fix: RecordingFix) =
        track.accept(fix, fix.elapsedRealtimeNanos)

    private fun fix(second: Long, north: Double, speed: Float? = null) =
        RecordingFix(
            recordedAt = 1_700_000_000_000 + second * 1_000,
            elapsedRealtimeNanos = nanos(second),
            latitude = metersToDegrees(north),
            longitude = 0.0,
            accuracyMeters = 1f,
            speedMetersPerSecond = speed,
            speedAccuracyMetersPerSecond = if (speed != null) 0.2f else null,
        )

    private fun nanos(seconds: Long) = seconds * 1_000_000_000

    private fun metersToDegrees(meters: Double) = Math.toDegrees(meters / 6_371_008.8)
}
