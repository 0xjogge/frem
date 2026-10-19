package dk.frem.app.recording

import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PressureMetricsProcessorTest {
    private fun pressure(height: Double) = 1013.25 * (1 - height / 44330).pow(1 / .190294957)

    @Test
    fun climbingAndDescendingAccumulateSeparately() {
        val processor = PressureMetricsProcessor(0)
        val heights =
            (0..20).map { it.toDouble() } +
                List(4) { 20.0 } +
                (19 downTo 0).map { it.toDouble() } +
                List(4) { 0.0 }
        val changes = heights.mapIndexed { i, height ->
            processor.accept(i * 1_000_000_000L, pressure(height), i * 1_000_000_000L, true)!!
        }
        assertEquals(20.0, changes.sumOf { it.ascent }, 2.1)
        assertEquals(20.0, changes.sumOf { it.descent }, 2.1)
    }

    @Test
    fun stationaryWeatherDriftAndSmallNoiseNeverBecomeClimbing() {
        val processor = PressureMetricsProcessor(0)
        val changes =
            (0..300).map { i ->
                processor.accept(
                    i * 1_000_000_000L,
                    pressure(i * .03 + sin(i.toDouble()) * .3),
                    i * 1_000_000_000L,
                    false,
                )!!
            }
        assertEquals(0.0, changes.sumOf { it.ascent + it.descent }, 0.0)
        val walking = PressureMetricsProcessor(0)
        (0..100).forEach { i ->
            assertEquals(
                PressureDelta(0.0, 0.0),
                walking.accept(
                    i * 1_000_000_000L,
                    pressure(sin(i.toDouble()) * .5),
                    i * 1_000_000_000L,
                    true,
                ),
            )
        }
    }

    @Test
    fun invalidSpikesAndSignalGapsDoNotJoinAltitudes() {
        val processor = PressureMetricsProcessor(1_000_000_000)
        assertNull(processor.accept(0, 1013.25, 1_000_000_000, true))
        assertNull(processor.accept(2_000_000_000, Double.NaN, 2_000_000_000, true))
        assertNotNull(processor.accept(2_000_000_000, pressure(0.0), 2_000_000_000, true))
        assertNull(processor.accept(3_000_000_000, pressure(100.0), 3_000_000_000, true))
        assertEquals(
            PressureDelta(0.0, 0.0),
            processor.accept(40_000_000_000, pressure(100.0), 40_000_000_000, true),
        )
    }
}
