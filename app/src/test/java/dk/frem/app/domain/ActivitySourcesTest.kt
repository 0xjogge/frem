package dk.frem.app.domain

import dk.frem.app.data.DaySummary
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ActivitySourcesTest {
    private val phone =
        DaySummary(
            "2026-10-19",
            steps = 3000,
            distance = 2100.5,
            source = "Phone recording",
            distanceSource = "Phone recording",
        )

    @Test
    fun healthStepsAndOtherMetricsSurvivePhoneDistanceFallback() {
        val health = DaySummary(phone.date, steps = 5000, activeCalories = 120.0, floors = 4.0)
        val merged = mergePhoneActivity(health, phone, readSteps = true, readDistance = true)
        assertEquals(5000L, merged.steps)
        assertEquals(2100.5, merged.distance!!, 0.0)
        assertEquals("Phone recording", merged.distanceSource)
        assertEquals(health.activeCalories, merged.activeCalories)
        assertEquals(health.floors, merged.floors)
        assertEquals("Phone recording + Health Connect", merged.source)
    }

    @Test
    fun recordedHealthDistanceWinsWithoutAddingOverlappingPhoneTotals() {
        val health = DaySummary(phone.date, steps = 5000, distance = 1800.0)
        val merged = mergePhoneActivity(health, phone, readSteps = true, readDistance = true)
        assertEquals(health, merged)
    }

    @Test
    fun genuineHealthZeroesAreNotReplacedByPhoneTotals() {
        val health = DaySummary(phone.date, steps = 0, distance = 0.0)
        assertEquals(
            health,
            mergePhoneActivity(health, phone, readSteps = true, readDistance = true),
        )
    }

    @Test
    fun stepsAloneDoNotInventDistanceAndMissingPhoneValuesDoNotBecomeZero() {
        val merged =
            mergePhoneActivity(
                DaySummary(phone.date, steps = 5000),
                phone.copy(steps = null, distance = null),
                readSteps = true,
                readDistance = true,
            )
        assertEquals(5000L, merged.steps)
        assertNull(merged.distance)
        assertEquals("Health Connect", merged.source)
    }

    @Test
    fun revokedDistancePermissionDoesNotMakeCachedDistanceOverridePhoneData() {
        val health = DaySummary(phone.date, steps = 5000, distance = 1800.0)
        val merged = mergePhoneActivity(health, phone, readSteps = true, readDistance = false)
        assertEquals(5000L, merged.steps)
        assertEquals(phone.distance, merged.distance)
        val missing =
            mergePhoneActivity(
                health,
                phone.copy(distance = null),
                readSteps = true,
                readDistance = false,
            )
        assertNull(missing.distance)
    }

    @Test
    fun phoneOnlyDaysAreReplacedWithFreshReadings() {
        assertEquals(
            phone,
            mergePhoneActivity(null, phone, readSteps = false, readDistance = false),
        )
        assertEquals(
            phone,
            mergePhoneActivity(
                phone.copy(steps = 1000, distance = 700.0),
                phone,
                readSteps = false,
                readDistance = false,
            ),
        )
    }

    @Test
    fun phoneDistanceOutlivesTheRecorderWindowButHealthDistanceDeletionsDoNot() {
        val cutoff = LocalDate.of(2026, 10, 10)
        val old = phone.copy(date = "2026-10-09")
        val imported = DaySummary(old.date, steps = 6000)
        val retained = retainOlderPhoneDistance(imported, old, cutoff)
        assertEquals(old.distance, retained.distance)
        assertEquals("Phone recording", retained.distanceSource)
        assertEquals(6000L, retained.steps)
        assertEquals(
            imported,
            retainOlderPhoneDistance(imported, old.copy(distanceSource = null), cutoff),
        )
        assertEquals(
            imported,
            retainOlderPhoneDistance(imported, old.copy(distanceSource = "Health Connect"), cutoff),
        )
    }

    @Test
    fun recentPhoneDistanceIsReReadAndNewHealthDistanceTakesPrecedenceOverOldEstimates() {
        val cutoff = LocalDate.of(2026, 10, 10)
        val imported = DaySummary(phone.date, steps = 6000)
        assertEquals(imported, retainOlderPhoneDistance(imported, phone, cutoff))
        val old = phone.copy(date = "2026-10-09")
        val health = DaySummary(old.date, distance = 2500.0)
        assertEquals(health, retainOlderPhoneDistance(health, old, cutoff))
    }
}
