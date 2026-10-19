package dk.frem.app.recording

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StepMetricsProcessorTest {
    @Test
    fun briskWalkingAndRunningAccumulateActualTimeRatherThanWeightedMinutes() {
        fun run(interval: Long): List<StepMetricDelta> {
            val processor = StepMetricsProcessor(0)
            return (1L..121).map { processor.accept(it * interval, it * interval)!! }
        }
        val walking = run(500_000_000)
        assertEquals(60_000, walking.sumOf { it.movingMillis })
        assertEquals(58_500, walking.sumOf { it.moderateMillis })
        assertEquals(0, walking.sumOf { it.vigorousMillis })
        val running = run(400_000_000)
        assertEquals(46_800, running.sumOf { it.vigorousMillis })
        assertEquals(0, running.sumOf { it.moderateMillis })
    }

    @Test
    fun staleDuplicateFutureAndStoppedStepsDoNotInventActivity() {
        val processor = StepMetricsProcessor(1_000_000_000)
        assertNull(processor.accept(0, 2_000_000_000))
        assertNull(processor.accept(3_000_000_000, 2_000_000_000))
        assertNotNull(processor.accept(1_000_000_000, 1_000_000_000))
        assertNull(processor.accept(1_000_000_000, 1_000_000_000))
        assertNull(processor.accept(2_000_000_000, 40_000_000_000))
        assertEquals(0, processor.accept(41_000_000_000, 41_000_000_000)!!.movingMillis)
        assertNull(processor.cadence(45_000_000_000))
    }

    @Test
    fun aStopResetsTheIntensityWindowAndLiveCadenceExpires() {
        val processor = StepMetricsProcessor(0)
        (1L..10).forEach { processor.accept(it * 500_000_000, it * 500_000_000) }
        assertEquals(120.0, processor.cadence(5_000_000_000)!!, .001)
        assertNull(processor.cadence(9_000_000_000))
        assertEquals(0, processor.accept(20_000_000_000, 20_000_000_000)!!.moderateMillis)
        assertNull(processor.cadence(20_000_000_000))
    }

    @Test
    fun midnightAndDstBoundariesPreserveMeasuredDuration() {
        val zone = ZoneId.of("Europe/Copenhagen")
        val start = Instant.parse("2026-10-24T21:59:59Z").toEpochMilli()
        val parts = mutableListOf<Pair<String, Long>>()
        splitMetricInterval(start, start + 2000, zone) { date, duration ->
            parts += date to duration
        }
        assertEquals(listOf("2026-10-24" to 1000L, "2026-10-25" to 1000L), parts)
        parts.clear()
        val dayStart =
            java.time.LocalDate.parse("2026-10-25").atStartOfDay(zone).toInstant().toEpochMilli()
        splitMetricInterval(dayStart, dayStart + 25 * 3_600_000L, zone) { date, duration ->
            parts += date to duration
        }
        assertEquals(listOf("2026-10-25" to 90_000_000L), parts)
    }
}
