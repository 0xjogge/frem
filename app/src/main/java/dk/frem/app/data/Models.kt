package dk.frem.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class LogKind(val title: String, val unit: String) {
    WEIGHT("Weight", "kg"),
    HYDRATION("Water", "ml"),
    HEIGHT("Height", "cm"),
    BODY_FAT("Body fat", "%"),
}

@Entity(tableName = "entries")
data class LogEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val date: String,
    val value: Double,
    val zone: String = ZoneId.systemDefault().id,
    val version: Long = 1,
    val source: String = "Frem",
    val healthId: String? = null,
    val syncState: String = "pending",
    val timestamp: Long = System.currentTimeMillis(),
) {
    val localDate: LocalDate
        get() = LocalDate.parse(date)

    val logKind: LogKind
        get() = LogKind.valueOf(kind)

    val editable: Boolean
        get() = source == "Frem"
}

@Entity(tableName = "days")
data class DaySummary(
    @PrimaryKey val date: String,
    val steps: Long? = null,
    val distance: Double? = null,
    val activeCalories: Double? = null,
    val totalCalories: Double? = null,
    val floors: Double? = null,
    val elevation: Double? = null,
    val heartAverage: Double? = null,
    val heartMin: Long? = null,
    val heartMax: Long? = null,
    val restingHeart: Long? = null,
    val sleepMinutes: Double? = null,
    val waterMl: Double? = null,
    val activeMinutes: Double? = null,
    val cadence: Double? = null,
    val moderateMinutes: Double? = null,
    val vigorousMinutes: Double? = null,
    val intensityMinutes: Long? = null,
    val heartLatest: Long? = null,
    val heartLatestTime: Long? = null,
    val source: String = "Health Connect",
    val updatedAt: Long = System.currentTimeMillis(),
    val distanceSource: String? = null,
) {
    val localDate: LocalDate
        get() = LocalDate.parse(date)
}

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey val id: String,
    val title: String,
    val type: Int,
    val start: Long,
    val end: Long,
    val source: String,
) {
    val minutes: Long
        get() = (end - start) / 60_000
}

@Entity(tableName = "sleep")
data class SleepNight(
    @PrimaryKey val id: String,
    val start: Long,
    val end: Long,
    val asleepMinutes: Double,
    val deepMinutes: Double?,
    val remMinutes: Double?,
    val lightMinutes: Double?,
    val awakeMinutes: Double?,
    val source: String,
)

enum class ReminderKind(val title: String) {
    WEIGHT("Weight check-in"),
    WATER("Water check-in"),
    STEPS("Evening step goal"),
}

data class ReminderSetting(
    val enabled: Boolean = false,
    val minuteOfDay: Int,
    val lastSent: String? = null,
)

data class Preferences(
    val stepGoal: Int = 8000,
    val waterGoal: Int = 2000,
    val theme: String = "system",
    val dynamicColor: Boolean = true,
    val imperial: Boolean = false,
    val backgroundSync: Boolean = false,
    val connected: Boolean = false,
    val weightReminder: ReminderSetting = ReminderSetting(minuteOfDay = 8 * 60 + 30),
    val waterReminder: ReminderSetting = ReminderSetting(minuteOfDay = 14 * 60),
    val stepReminder: ReminderSetting = ReminderSetting(minuteOfDay = 19 * 60),
) {
    fun reminder(kind: ReminderKind) =
        when (kind) {
            ReminderKind.WEIGHT -> weightReminder
            ReminderKind.WATER -> waterReminder
            ReminderKind.STEPS -> stepReminder
        }
}

data class HourSteps(val hour: Int, val steps: Long?)

data class Highlight(val title: String, val detail: String, val metric: String = "Steps")

data class StepRecords(
    val streak: Int,
    val bestDay: DaySummary?,
    val goalsLastWeek: Int,
    val average: Long?,
)
