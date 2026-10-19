package dk.frem.app.domain

import dk.frem.app.data.DaySummary
import dk.frem.app.recording.ActivityDayMetrics
import org.junit.Assert.*
import org.junit.Test

class RecordedActivityDaysTest {
    private val native =
        ActivityDayMetrics(
            "walk",
            "2026-10-19",
            steps = 120,
            cadenceSteps = 120.0,
            cadenceMillis = 60_000,
            moderateMillis = 30_000,
            vigorousMillis = 20_000,
            ascentMeters = 12.0,
            descentMeters = 6.0,
            floorAscentMeters = 9.0,
            elevationSource = "Barometer",
        )

    @Test
    fun sourceSelectionIsPerMetricAndPreservesRealZeroes() {
        val health = DaySummary(native.date, floors = 0.0, activeMinutes = 5.0)
        val result = recordedActivityDays(listOf(health), listOf(native))
        val day = result.days.single()
        assertEquals(0.0, day.floors!!, 0.0)
        assertEquals(5.0, day.activeMinutes!!, 0.0)
        assertEquals(120.0, day.cadence!!, 0.0)
        assertEquals(12.0, day.elevation!!, 0.0)
        assertEquals(6.0, day.elevationLost!!, 0.0)
        assertFalse(result.nativeSources[native.date]!!.containsKey("floors"))
        assertTrue(result.nativeSources[native.date]!!.containsKey("cadence"))
    }

    @Test
    fun nativeAveragesAreDurationWeightedAndEmptyCoverageRemainsUnknown() {
        val second = native.copy(sessionId = "run", cadenceSteps = 360.0, cadenceMillis = 120_000)
        val day = recordedActivityDays(emptyList(), listOf(native, second)).days.single()
        assertEquals(160.0, day.cadence!!, .001)
        assertEquals(100.0 / 60, day.activeMinutes!!, .001)
        val empty =
            recordedActivityDays(emptyList(), listOf(ActivityDayMetrics("no-sensor", native.date)))
                .days
                .single()
        assertNull(empty.cadence)
        assertNull(empty.activeMinutes)
        assertNull(empty.floors)
        assertNull(empty.elevation)
    }

    @Test
    fun replacingOrDeletingHealthDataRevealsTheNativeSubtotalWithoutChangingIt() {
        val health = DaySummary(native.date, elevation = 100.0)
        assertEquals(
            100.0,
            recordedActivityDays(listOf(health), listOf(native)).days.single().elevation!!,
            0.0,
        )
        assertEquals(
            12.0,
            recordedActivityDays(listOf(health.copy(elevation = null)), listOf(native))
                .days
                .single()
                .elevation!!,
            0.0,
        )
        assertEquals(12.0, native.ascentMeters!!, 0.0)
    }
}
