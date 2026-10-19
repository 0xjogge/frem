package dk.frem.app.recording

/** Active time uses the boot's monotonic clock, never wall time or fix timestamps. */
internal class RecordingTimeline(durationMillis: Long = 0, runningSinceNanos: Long? = null) {
    private var savedMillis = durationMillis.coerceAtLeast(0)
    private var runningSince = runningSinceNanos

    fun durationMillis(nowNanos: Long): Long {
        val extra = runningSince?.let { (nowNanos - it).coerceAtLeast(0) / 1_000_000 } ?: 0
        return if (Long.MAX_VALUE - savedMillis < extra) Long.MAX_VALUE else savedMillis + extra
    }

    fun pause(nowNanos: Long) {
        savedMillis = durationMillis(nowNanos)
        runningSince = null
    }

    fun resume(nowNanos: Long) {
        if (runningSince == null) runningSince = nowNanos
    }
}
