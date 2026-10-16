package dk.frem.app.domain

import dk.frem.app.data.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val now = ZonedDateTime.of(2026, 10, 14, 19, 0, 0, 0, ZoneId.of("Europe/Copenhagen"))
    private val enabled =
        Preferences(
            weightReminder = ReminderSetting(true, 510),
            waterReminder = ReminderSetting(true, 840),
            stepReminder = ReminderSetting(true, 1140),
        )

    @Test
    fun dailySchedulingKeepsLocalTimeAcrossBothDaylightSavingChanges() {
        val spring = ZonedDateTime.of(2026, 3, 28, 8, 30, 0, 0, now.zone)
        val autumn = ZonedDateTime.of(2026, 10, 24, 8, 30, 0, 0, now.zone)
        val afterSpring = nextReminderTime(spring, 510)
        val afterAutumn = nextReminderTime(autumn, 510)
        assertEquals(LocalTime.of(8, 30), afterSpring.toLocalTime())
        assertEquals(23L, Duration.between(spring, afterSpring).toHours())
        assertEquals(25L, Duration.between(autumn, afterAutumn).toHours())
    }

    @Test
    fun passedTimeSchedulesTomorrowAndFutureTimeSchedulesToday() {
        assertEquals(now.toLocalDate().plusDays(1), nextReminderTime(now, 1140).toLocalDate())
        assertEquals(now.toLocalDate(), nextReminderTime(now, 1200).toLocalDate())
    }

    @Test
    fun completedWeightCheckInAndWaterGoalSuppressTheirReminders() {
        val entries =
            listOf(
                LogEntry(kind = "WEIGHT", date = now.toLocalDate().toString(), value = 80.0),
                LogEntry(kind = "HYDRATION", date = now.toLocalDate().toString(), value = 1000.0),
                LogEntry(
                    kind = "HYDRATION",
                    date = now.toLocalDate().toString(),
                    value = 1000.0,
                    source = "other.app",
                ),
            )
        assertNull(reminderMessage(ReminderKind.WEIGHT, entries, null, enabled, now))
        assertNull(reminderMessage(ReminderKind.WATER, entries, null, enabled, now))
    }

    @Test
    fun achievedStepGoalSuppressesNotificationEvenWhenCacheIsOld() {
        val day =
            DaySummary(
                now.toLocalDate().toString(),
                steps = 8000,
                updatedAt = now.minusHours(3).toInstant().toEpochMilli(),
            )
        assertNull(reminderMessage(ReminderKind.STEPS, emptyList(), day, enabled, now))
    }

    @Test
    fun staleAndWrongDayCachesNeverProduceAClaimAboutRemainingSteps() {
        val day =
            DaySummary(
                now.toLocalDate().toString(),
                steps = 1000,
                updatedAt = now.minusHours(2).toInstant().toEpochMilli(),
            )
        val stale = reminderMessage(ReminderKind.STEPS, emptyList(), day, enabled, now)!!
        val previousDay =
            reminderMessage(
                ReminderKind.STEPS,
                emptyList(),
                day.copy(date = now.toLocalDate().minusDays(1).toString()),
                enabled,
                now,
            )!!
        assertFalse(stale.body.contains("steps from"))
        assertFalse(previousDay.body.contains("steps from"))
        val fresh =
            reminderMessage(
                ReminderKind.STEPS,
                emptyList(),
                day.copy(updatedAt = now.minusMinutes(2).toInstant().toEpochMilli()),
                enabled,
                now,
            )!!
        assertTrue(fresh.body.contains("latest reading"))
    }

    @Test
    fun disabledAndAlreadySentRemindersStayQuiet() {
        assertNull(reminderMessage(ReminderKind.WEIGHT, emptyList(), null, Preferences(), now))
        val sent =
            enabled.copy(
                weightReminder =
                    enabled.weightReminder.copy(lastSent = now.toLocalDate().toString()),
            )
        assertNull(reminderMessage(ReminderKind.WEIGHT, emptyList(), null, sent, now))
    }
}
