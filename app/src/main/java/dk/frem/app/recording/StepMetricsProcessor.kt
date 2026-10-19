package dk.frem.app.recording

internal data class StepMetricDelta(
    val elapsedNanos: Long,
    val intervalStartNanos: Long?,
    val movingMillis: Long,
    val moderateMillis: Long,
    val vigorousMillis: Long,
)

/** Hardware step times, not callback arrival times. No intensity is inferred across a stop. */
internal class StepMetricsProcessor(private val startedAtNanos: Long) {
    private var lastStep: Long? = null
    private val window = ArrayDeque<Long>()

    fun accept(elapsed: Long, now: Long): StepMetricDelta? {
        if (elapsed < startedAtNanos || elapsed > now || now - elapsed > 30_000_000_000L)
            return null
        val previous = lastStep
        if (previous != null && elapsed - previous < 200_000_000L) return null
        lastStep = elapsed
        val continuous = previous != null && elapsed - previous <= 2_000_000_000L
        if (!continuous) window.clear()
        window.addLast(elapsed)
        while (window.size > 1 && elapsed - window.first() > 20_000_000_000L) window.removeFirst()
        val duration = if (continuous) (elapsed - previous!!) / 1_000_000 else 0L
        // Several successive steps are needed before a cadence becomes an intensity estimate.
        val rate = cadence(elapsed).takeIf { window.size >= 5 }
        return StepMetricDelta(
            elapsed,
            previous.takeIf { continuous },
            duration,
            if (rate != null && rate >= 100 && rate < 130) duration else 0,
            if (rate != null && rate >= 130) duration else 0,
        )
    }

    fun cadence(now: Long): Double? {
        val last = lastStep ?: return null
        if (now < last || now - last > 3_000_000_000L || window.size < 2) return null
        val span = window.last() - window.first()
        return if (span > 0) (window.size - 1) * 60e9 / span else null
    }

    fun walkingAt(elapsed: Long): Boolean =
        window.count { it <= elapsed && elapsed - it <= 5_000_000_000L } >= 3
}
