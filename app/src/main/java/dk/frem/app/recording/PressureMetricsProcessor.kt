package dk.frem.app.recording

import kotlin.math.abs
import kotlin.math.pow

internal data class PressureDelta(val ascent: Double, val descent: Double)

/**
 * Relative pressure altitude: weather drift while stationary is rebased, never counted as travel.
 */
internal class PressureMetricsProcessor(private val startedAt: Long) {
    private var origin: Double? = null
    private var last: Pair<Long, Double>? = null
    private val window = ArrayDeque<Double>()
    private var reference: Double? = null

    fun accept(elapsed: Long, pressure: Double, now: Long, moving: Boolean): PressureDelta? {
        if (
            !pressure.isFinite() ||
                pressure !in 300.0..1100.0 ||
                elapsed < startedAt ||
                elapsed > now ||
                now - elapsed > 30_000_000_000L
        )
            return null
        val initial = origin ?: pressure.also { origin = it }
        val altitude = 44330.0 * (1 - (pressure / initial).pow(.190294957))
        val previous = last
        if (previous != null) {
            val duration = (elapsed - previous.first) / 1e9
            if (duration <= 0) return null
            if (duration > 30) {
                window.clear()
                reference = null
            } else if (abs(altitude - previous.second) > duration * 4 + 1) return null
        }
        last = elapsed to altitude
        window.addLast(altitude)
        if (window.size > 3) window.removeFirst()
        val filtered = window.sorted()[window.size / 2]
        val baseline = reference
        if (!moving || baseline == null) {
            reference = filtered
            return PressureDelta(0.0, 0.0)
        }
        val change = filtered - baseline
        if (abs(change) < 2) return PressureDelta(0.0, 0.0)
        reference = filtered
        return PressureDelta(change.coerceAtLeast(0.0), (-change).coerceAtLeast(0.0))
    }
}
