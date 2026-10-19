package dk.frem.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimelineTest {
    @Test
    fun onlyRunningIntervalsCountAndRepeatedControlsAreIdempotent() {
        val clock = RecordingTimeline(runningSinceNanos = seconds(10))
        assertEquals(2_000, clock.durationMillis(seconds(12)))
        clock.pause(seconds(15))
        clock.pause(seconds(30))
        assertEquals(5_000, clock.durationMillis(seconds(100)))
        clock.resume(seconds(100))
        clock.resume(seconds(102))
        assertEquals(9_000, clock.durationMillis(seconds(104)))
    }

    @Test
    fun recoveryUsesOnlyCheckpointDurationAndResumeDoesNotCountTheGap() {
        val recovered = RecordingTimeline(durationMillis = 42_250)
        assertEquals(42_250, recovered.durationMillis(seconds(90_000)))
        recovered.resume(seconds(90_000))
        assertEquals(44_750, recovered.durationMillis(seconds(90_002) + 500_000_000))
    }

    @Test
    fun elapsedClockCannotProduceNegativeTimeOrOverflowSavedDuration() {
        val clock = RecordingTimeline(durationMillis = 700, runningSinceNanos = seconds(10))
        assertEquals(700, clock.durationMillis(seconds(9)))
        val almostFull = RecordingTimeline(Long.MAX_VALUE - 10, seconds(1))
        assertEquals(Long.MAX_VALUE, almostFull.durationMillis(seconds(2)))
    }

    @Test
    fun checkpointsDoNotLoseSubsecondTime() {
        val clock = RecordingTimeline(runningSinceNanos = seconds(1))
        assertEquals(333, clock.durationMillis(seconds(1) + 333_999_999))
        assertEquals(666, clock.durationMillis(seconds(1) + 666_999_999))
        assertEquals(1_001, clock.durationMillis(seconds(2) + 1_000_000))
    }

    private fun seconds(value: Long) = value * 1_000_000_000
}
