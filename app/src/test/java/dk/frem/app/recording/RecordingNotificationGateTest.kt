package dk.frem.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingNotificationGateTest {
    @Test
    fun frequentFixesPermitAtMostOneRegularPostEveryFiveSeconds() {
        val gate = RecordingNotificationGate()
        val posts =
            (0L..10_000L step 100).filter { now ->
                gate.due("session", RecordingState.RECORDING, now).also { due ->
                    if (due) gate.posted("session", RecordingState.RECORDING, now)
                }
            }
        assertEquals(listOf(0L, 5_000L, 10_000L), posts)
    }

    @Test
    fun pauseAndResumePostImmediatelyAndResetTheRegularCooldown() {
        val gate = RecordingNotificationGate()
        gate.posted("session", RecordingState.RECORDING, 0)
        assertTrue(gate.due("session", RecordingState.PAUSED, 2_000))
        gate.posted("session", RecordingState.PAUSED, 2_000)
        assertTrue(gate.due("session", RecordingState.RECORDING, 2_500))
        gate.posted("session", RecordingState.RECORDING, 2_500)
        assertFalse(gate.due("session", RecordingState.RECORDING, 7_499))
        assertTrue(gate.due("session", RecordingState.RECORDING, 7_500))
    }

    @Test
    fun aNewSessionCannotBeHiddenByThePreviousSessionsCooldown() {
        val gate = RecordingNotificationGate()
        gate.posted("previous", RecordingState.RECORDING, 10_000)
        assertTrue(gate.due("new", RecordingState.RECORDING, 10_001))
        gate.posted("new", RecordingState.RECORDING, 10_001)
        assertFalse(gate.due("new", RecordingState.RECORDING, 10_002))
    }

    @Test
    fun aRegressedClockDoesNotCauseRepeatedPosts() {
        val gate = RecordingNotificationGate()
        gate.posted("session", RecordingState.RECORDING, 10_000)
        assertFalse(gate.due("session", RecordingState.RECORDING, 9_000))
        assertTrue(gate.due("session", RecordingState.RECORDING, 15_000))
    }
}
