package dk.frem.app.recording

internal data class RecordingSamplingPolicy(val intervalMillis: Long, val maxDelayMillis: Long) {
    companion object {
        fun forActivity(type: ActivityType, interactive: Boolean): RecordingSamplingPolicy {
            val interval =
                when (type) {
                    ActivityType.WALKING -> 2_000L
                    ActivityType.HIKING -> 3_000L
                    else -> 1_000L
                }
            // GMS only batches when the maximum delay is at least twice the sample interval.
            // Six seconds for hiking still leaves headroom under the processor's 10 s age limit.
            val delay = if (interactive) 0L else maxOf(5_000L, interval * 2)
            return RecordingSamplingPolicy(interval, delay)
        }
    }
}
