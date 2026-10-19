package dk.frem.app.recording

import org.junit.Assert.*
import org.junit.Test

class ReorderBufferTest {
    @Test
    fun newerBatchBeforeOlderRetainsEveryFixWithinTheBatchWindow() {
        val buffer = batched(6_000)
        val output = mutableListOf<RecordingFix>()
        output += buffer.offer(samples(9, 10, 11, 12), seconds(13))
        assertTrue(output.isEmpty())
        output += buffer.offer(samples(8, 7), seconds(13))
        assertEquals(listOf(7L), timestamps(output))
        output += drainQuietly(buffer)
        assertEquals((7L..12L).toList(), timestamps(output))
    }

    @Test
    fun arbitraryCallbackOrderWithinSixSecondDeliveryBoundIsLossless() {
        val buffer = batched(6_000)
        val output = mutableListOf<RecordingFix>()
        val callbacks =
            (100L..140L)
                .map { sample -> sample to sample + (sample * 37 % 7) }
                .sortedWith(compareBy<Pair<Long, Long>> { it.second }.thenByDescending { it.first })
        for ((sample, arrival) in callbacks) {
            val ready = buffer.offer(samples(sample), seconds(arrival))
            assertTrue(ready.all { it.elapsedRealtimeNanos <= seconds(arrival) })
            assertTrue(ready.all { seconds(arrival) - it.elapsedRealtimeNanos <= seconds(10) })
            output += ready
        }
        output += drainQuietly(buffer)
        assertEquals((100L..140L).toList(), timestamps(output))
    }

    @Test
    fun duplicateFixesAreSuppressedAcrossPendingAndAlreadyEmittedCallbacks() {
        val buffer = batched(6_000)
        assertTrue(buffer.offer(samples(102, 102, 103), seconds(104)).isEmpty())
        assertTrue(buffer.offer(samples(103, 102), seconds(106)).isEmpty())
        val first = buffer.drain(seconds(111))
        assertEquals(listOf(102L, 103L), timestamps(first))
        assertTrue(buffer.offer(samples(102, 103), seconds(111)).isEmpty())
        assertNull(buffer.nextDrainAtNanos())
    }

    @Test
    fun aQuietPeriodDrainsTheLastBatchWithoutAnotherLocationCallback() {
        val buffer = batched(5_000)
        assertTrue(buffer.offer(samples(101, 102, 103, 104, 105), seconds(105)).isEmpty())
        // One quiet-period wake drains the whole batch, rather than polling once per sample.
        assertEquals(seconds(110), buffer.nextDrainAtNanos())
        assertEquals((101L..105L).toList(), timestamps(buffer.drain(seconds(110))))
        assertNull(buffer.nextDrainAtNanos())
    }

    @Test
    fun normalScreenOnDeliveryHasNoHoldbackOrTimer() {
        val buffer = ReorderBuffer()
        buffer.beforeRequest(0, seconds(100))
        buffer.requestApplied(seconds(100))
        assertEquals(listOf(100L), timestamps(buffer.offer(samples(100), seconds(100))))
        assertEquals(listOf(101L), timestamps(buffer.offer(samples(101), seconds(101))))
        assertNull(buffer.nextDrainAtNanos())
    }

    @Test
    fun screenOnTransitionQuarantinesPendingOldCallbacksThenReturnsToImmediateDelivery() {
        val buffer = batched(6_000)
        val output = mutableListOf<RecordingFix>()
        output += buffer.offer(samples(109), seconds(109))
        output += buffer.beforeRequest(0, seconds(109))
        // A replacement/flush acknowledgement is not proof that old callbacks were delivered.
        output += buffer.requestApplied(seconds(110))
        output += buffer.offer(samples(110, 111), seconds(111))
        assertTrue(output.isEmpty())
        output += buffer.offer(samples(107, 106), seconds(112))
        assertEquals(listOf(106L), timestamps(output))
        output += buffer.offer(samples(108), seconds(114))
        assertEquals(listOf(106L, 107L, 108L), timestamps(output))
        assertEquals(seconds(116) + 1, buffer.nextDrainAtNanos())
        output += buffer.drain(seconds(116) + 1)
        assertEquals((106L..111L).toList(), timestamps(output))
        assertEquals(listOf(117L), timestamps(buffer.offer(samples(117), seconds(117))))
        assertNull(buffer.nextDrainAtNanos())
    }

    @Test
    fun retiredRequestDeadlineIncludesACallbackAtExactlyTheLatencyBound() {
        val buffer = batched(6_000)
        buffer.beforeRequest(0, seconds(109))
        buffer.requestApplied(seconds(110))
        buffer.offer(samples(111), seconds(111))
        // A timer at the exact boundary cannot commit 111 ahead of the retired request's 110.
        assertTrue(buffer.drain(seconds(116)).isEmpty())
        assertEquals(listOf(110L), timestamps(buffer.offer(samples(110), seconds(116))))
        assertEquals(listOf(111L), timestamps(buffer.drain(seconds(116) + 1)))
    }

    @Test
    fun returningToScreenOffDuringQuarantineDoesNotPrematurelyReleaseNewSamples() {
        val buffer = batched(6_000)
        buffer.beforeRequest(0, seconds(100))
        buffer.requestApplied(seconds(100))
        buffer.offer(samples(101), seconds(101))
        assertTrue(buffer.beforeRequest(6_000, seconds(102)).isEmpty())
        buffer.requestApplied(seconds(102))
        assertTrue(buffer.offer(samples(103), seconds(103)).isEmpty())
        assertEquals(listOf(101L), timestamps(buffer.drain(seconds(107))))
        assertEquals(listOf(103L), timestamps(buffer.drain(seconds(109))))
    }

    @Test
    fun futureNegativeAndStaleSamplesAreNotAdmittedOrAllowedToPoisonTheWatermark() {
        val buffer = ReorderBuffer()
        val malformed =
            samples(89, 101) +
                listOf(
                    fix(100).copy(elapsedRealtimeNanos = Long.MIN_VALUE),
                    fix(100).copy(elapsedRealtimeNanos = Long.MAX_VALUE),
                )
        assertTrue(buffer.offer(malformed, seconds(100)).isEmpty())
        assertEquals(listOf(100L), timestamps(buffer.offer(samples(100), seconds(100))))
        assertEquals(listOf(101L), timestamps(buffer.offer(samples(101), seconds(101))))
    }

    @Test
    fun tenSecondFreshnessBoundaryIsCheckedOnAdmission() {
        val buffer = ReorderBuffer()
        val fixes = listOf(fix(90).copy(elapsedRealtimeNanos = seconds(90) - 1), fix(90), fix(100))
        assertEquals(listOf(90L, 100L), timestamps(buffer.offer(fixes, seconds(100))))
    }

    @Test
    fun freshButUnseenFixBehindCommittedOutputFailsInsteadOfSilentlySkippingIt() {
        val buffer = ReorderBuffer()
        buffer.offer(samples(100), seconds(100))
        val error =
            assertThrows(ReorderBuffer.OrderingException::class.java) {
                buffer.offer(samples(99), seconds(101))
            }
        assertTrue(error.message!!.contains("ordering window"))
    }

    @Test
    fun oldCallbacksBeyondTheScreenOnQuarantineFailExplicitly() {
        val buffer = batched(6_000)
        buffer.beforeRequest(0, seconds(109))
        buffer.requestApplied(seconds(110))
        buffer.offer(samples(116), seconds(116) + 1)
        // Still fresh (age 9 s), but outside the retired request's 6 s delivery bound.
        assertThrows(ReorderBuffer.OrderingException::class.java) {
            buffer.offer(samples(108), seconds(117))
        }
    }

    @Test
    fun aTimerDelayedBeyondFreshnessFailsRatherThanEmittingExpiredQueuedFixes() {
        val buffer = batched(6_000)
        buffer.offer(samples(101, 102), seconds(103))
        assertThrows(ReorderBuffer.OrderingException::class.java) {
            buffer.drain(seconds(112))
        }
    }

    @Test
    fun overflowIsExplicitForBothPendingSamplesAndDeduplicationHistory() {
        val pending = ReorderBuffer(capacity = 2)
        pending.beforeRequest(6_000, seconds(100))
        assertThrows(ReorderBuffer.OrderingException::class.java) {
            pending.offer(samples(101, 102, 103), seconds(104))
        }
        val history = ReorderBuffer(capacity = 2)
        history.offer(samples(100, 101), seconds(101))
        assertThrows(ReorderBuffer.OrderingException::class.java) {
            history.offer(samples(102), seconds(102))
        }
    }

    @Test
    fun closingTheBufferDropsQueuedReferencesAndTheTimerDeadline() {
        val buffer = batched(6_000)
        buffer.offer(samples(101), seconds(102))
        assertNotNull(buffer.nextDrainAtNanos())
        buffer.clear()
        assertNull(buffer.nextDrainAtNanos())
        assertTrue(buffer.drain(seconds(110)).isEmpty())
    }

    @Test
    fun controlFlushDrainsTheEntireKnownQuarantinedTailOnceWithoutInventingATailPoint() {
        val buffer = batched(6_000)
        val tail = samples(101, 102, 103, 104)
        assertTrue(buffer.offer(listOf(tail[2], tail[0], tail[1], tail[2]), seconds(104)).isEmpty())
        buffer.beforeRequest(0, seconds(104))
        buffer.requestApplied(seconds(104))
        assertTrue(buffer.offer(listOf(tail[3]), seconds(104)).isEmpty())
        assertNotNull(buffer.nextDrainAtNanos())
        // The clock is newer than the last sample; flushing must not synthesize a point at 105.
        assertEquals(tail, buffer.flush(seconds(105)))
        assertNull(buffer.nextDrainAtNanos())
        assertTrue(buffer.flush(seconds(105)).isEmpty())
        assertTrue(buffer.offer(tail, seconds(105)).isEmpty())
        buffer.clear()
        assertNull(buffer.nextDrainAtNanos())
        assertTrue(buffer.flush(seconds(106)).isEmpty())
    }

    @Test
    fun controlFlushPreservesFreshnessAndDoesNotAdmitExpiredQueuedPoints() {
        val buffer = batched(6_000)
        buffer.offer(samples(101), seconds(102))
        assertThrows(ReorderBuffer.OrderingException::class.java) {
            buffer.flush(seconds(112))
        }
    }

    @Test
    fun controlFlushCannotPromiseOrderingForAnUnknownCallbackArrivingAfterTheBarrier() {
        val buffer = batched(6_000)
        buffer.offer(samples(102, 104), seconds(104))
        assertEquals(listOf(102L, 104L), timestamps(buffer.flush(seconds(104))))
        assertThrows(ReorderBuffer.OrderingException::class.java) {
            buffer.offer(samples(103), seconds(105))
        }
    }

    private fun batched(delayMillis: Long) =
        ReorderBuffer().also {
            it.beforeRequest(delayMillis, 0)
            it.requestApplied(0)
        }

    private fun drainQuietly(buffer: ReorderBuffer): List<RecordingFix> {
        val output = mutableListOf<RecordingFix>()
        repeat(128) {
            val deadline = buffer.nextDrainAtNanos() ?: return output
            val ready = buffer.drain(deadline)
            assertTrue(ready.all { deadline - it.elapsedRealtimeNanos <= seconds(10) })
            output += ready
        }
        error("Quiet-period drain did not converge")
    }

    private fun samples(vararg values: Long) = values.map(::fix)

    private fun fix(timestamp: Long) =
        RecordingFix(
            recordedAt = 1_700_000_000_000 + timestamp * 1_000,
            elapsedRealtimeNanos = seconds(timestamp),
            latitude = 55.0,
            longitude = 12.0,
            accuracyMeters = 3f,
        )

    private fun timestamps(fixes: List<RecordingFix>) = fixes.map {
        it.elapsedRealtimeNanos / 1_000_000_000
    }

    private fun seconds(value: Long) = value * 1_000_000_000
}
