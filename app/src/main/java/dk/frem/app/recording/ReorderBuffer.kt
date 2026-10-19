package dk.frem.app.recording

import java.util.TreeMap
import java.util.TreeSet

/**
 * Bounded event-time ordering for one fused-location subscription. The caller serializes access.
 *
 * With a batch latency L, observations are held until timestamp <= monotonicNow - L. Thus a fix
 * delivered within L of its sample time precedes any newer emitted fix, irrespective of callback
 * order. Non-batched delivery is immediate. Switching back to non-batched delivery retains the old
 * window until L after replacement acknowledgement, covering callbacks from the retired request.
 *
 * GMS does not guarantee a delivery deadline. An unseen fresh fix behind committed output, capacity
 * overflow, or an expired queued fix fails explicitly; this cannot provide unbounded ordering.
 */
internal class ReorderBuffer(private val capacity: Int = 128) {
    class OrderingException(message: String) : IllegalStateException(message)

    private val pending = TreeMap<Long, RecordingFix>()
    private val emitted = TreeSet<Long>()
    private var batchLatencyNanos = 0L
    private var retiringLatencyNanos = 0L
    private var awaitingReplacement = false
    private var quarantineUntilNanos: Long? = null
    private var lastNowNanos = 0L
    private var releaseWatermark = Long.MIN_VALUE
    private var lastEmittedNanos = Long.MIN_VALUE

    init {
        require(capacity > 0)
    }

    /** Call before flushing/replacing the GMS request, so even its in-flight callbacks are held. */
    fun beforeRequest(maxBatchDelayMillis: Long, nowNanos: Long): List<RecordingFix> {
        require(maxBatchDelayMillis in 0..6_000)
        if (maxBatchDelayMillis > 0) {
            batchLatencyNanos = maxBatchDelayMillis * 1_000_000
            retiringLatencyNanos = 0
            awaitingReplacement = false
            quarantineUntilNanos = null
        } else {
            retiringLatencyNanos = maxOf(batchLatencyNanos, retiringLatencyNanos)
            batchLatencyNanos = 0
            awaitingReplacement = retiringLatencyNanos > 0
            quarantineUntilNanos = null
        }
        return drain(nowNanos)
    }

    /** An acknowledged non-batched request can still have older batched callbacks in flight. */
    fun requestApplied(nowNanos: Long): List<RecordingFix> {
        if (awaitingReplacement) {
            awaitingReplacement = false
            // Keep the deadline inclusive: a callback at exactly sampleTime + L must still be
            // admissible even if a timer at that same monotonic instant ran first.
            quarantineUntilNanos = nowNanos + retiringLatencyNanos + 1
        }
        return drain(nowNanos)
    }

    fun offer(fixes: List<RecordingFix>, nowNanos: Long): List<RecordingFix> {
        advanceClock(nowNanos)
        for (fix in fixes) {
            val timestamp = fix.elapsedRealtimeNanos
            // Reject invalid times before subtraction, so extremes cannot overflow age arithmetic.
            if (timestamp < 0 || timestamp > nowNanos || nowNanos - timestamp > MAX_AGE_NANOS)
                continue
            if (timestamp in emitted || pending.containsKey(timestamp)) continue
            if (timestamp <= lastEmittedNanos) {
                throw OrderingException(
                    "GPS data arrived outside the ordering window. Resume to continue recording.",
                )
            }
            pending[timestamp] = fix
            if (pending.size > capacity) throw overflow()
        }
        return drain(nowNanos)
    }

    fun drain(nowNanos: Long): List<RecordingFix> {
        advanceClock(nowNanos)
        val quarantineEnd = quarantineUntilNanos
        if (!awaitingReplacement && quarantineEnd != null && nowNanos >= quarantineEnd) {
            retiringLatencyNanos = 0
            quarantineUntilNanos = null
        }
        val latency = maxOf(batchLatencyNanos, retiringLatencyNanos)
        releaseWatermark = maxOf(releaseWatermark, nowNanos - latency)
        val ready = mutableListOf<RecordingFix>()
        while (pending.isNotEmpty() && pending.firstKey() <= releaseWatermark) {
            if (emitted.size >= capacity) throw overflow()
            val (timestamp, fix) = checkNotNull(pending.pollFirstEntry())
            lastEmittedNanos = timestamp
            emitted.add(timestamp)
            ready.add(fix)
        }
        return ready
    }

    /**
     * Control-stop drain of every known fresh observation, including the quarantined tail. This
     * advances the committed watermark; it cannot promise ordering for unknown callbacks arriving
     * after the drain. The source places a consumer barrier after these fixes and then closes.
     */
    fun flush(nowNanos: Long): List<RecordingFix> {
        advanceClock(nowNanos)
        releaseWatermark = maxOf(releaseWatermark, nowNanos)
        return drain(nowNanos)
    }

    /**
     * One-shot quiet-period deadline, not a per-sample polling timer. Nine-second sample age leaves
     * one second for scheduling/downstream work before the processor's ten-second freshness limit.
     * Incoming callbacks advance the watermark earlier; screen-off timers are normally superseded
     * by the next batch. Quarantine expiry can release the entire transition backlog sooner.
     */
    fun nextDrainAtNanos(): Long? {
        if (pending.isEmpty()) return null
        val ageDeadline = pending.firstKey() + QUIET_DRAIN_AGE_NANOS
        return quarantineUntilNanos?.let { minOf(ageDeadline, it) } ?: ageDeadline
    }

    fun clear() {
        pending.clear()
        emitted.clear()
    }

    private fun advanceClock(nowNanos: Long) {
        require(nowNanos >= lastNowNanos) { "Location ordering requires a monotonic clock." }
        lastNowNanos = nowNanos
        while (emitted.isNotEmpty() && nowNanos - emitted.first() > MAX_AGE_NANOS) emitted
            .pollFirst()
        if (pending.isNotEmpty() && nowNanos - pending.firstKey() > MAX_AGE_NANOS) {
            throw OrderingException(
                "Queued GPS data expired before delivery. Resume to continue recording.",
            )
        }
    }

    private fun overflow() =
        OrderingException("GPS ordering fell behind. Resume to continue recording.")

    companion object {
        private const val MAX_AGE_NANOS = 10_000_000_000L
        private const val QUIET_DRAIN_AGE_NANOS = 9_000_000_000L
    }
}
