package dk.frem.app.domain

import dk.frem.app.data.*
import java.text.NumberFormat
import java.time.*

data class ReminderMessage(val title: String, val body: String)

fun nextReminderTime(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime {
    require(minuteOfDay in 0..1439)
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val candidate = now.toLocalDate().atTime(time).atZone(now.zone)
    return if (candidate > now) candidate
    else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
}

fun reminderMessage(
    kind: ReminderKind,
    entries: List<LogEntry>,
    day: DaySummary?,
    preferences: Preferences,
    now: ZonedDateTime,
): ReminderMessage? {
    val today = now.toLocalDate()
    val setting = preferences.reminder(kind)
    if (!setting.enabled || setting.lastSent == today.toString()) return null
    val todaysEntries = entries.filter { it.localDate == today }
    return when (kind) {
        ReminderKind.WEIGHT ->
            if (todaysEntries.any { it.logKind == LogKind.WEIGHT }) null
            else
                ReminderMessage(
                    "A moment to check in",
                    "Log today’s weight in Frem when it suits you.",
                )
        ReminderKind.WATER -> {
            val logged = todaysEntries.filter { it.logKind == LogKind.HYDRATION }.sumOf { it.value }
            val cached = day?.takeIf { it.localDate == today }?.waterMl ?: 0.0
            if (maxOf(logged, cached) >= preferences.waterGoal) null
            else
                ReminderMessage(
                    "A little water break",
                    "Time for a drink? Open Frem to log water and check your daily goal.",
                )
        }
        ReminderKind.STEPS -> {
            val current = day?.takeIf { it.localDate == today }
            if ((current?.steps ?: -1) >= preferences.stepGoal) null
            else {
                val fresh =
                    current?.steps != null &&
                        current.updatedAt <= now.toInstant().toEpochMilli() &&
                        now.toInstant().toEpochMilli() - current.updatedAt < 60 * 60_000
                ReminderMessage(
                    "A little movement goes a long way",
                    if (fresh)
                        "Your latest reading is ${NumberFormat.getIntegerInstance().format(preferences.stepGoal - current!!.steps!!)} steps from your goal. Open Frem to check in."
                    else "Time for a little movement? Open Frem to see today’s progress.",
                )
            }
        }
    }
}
