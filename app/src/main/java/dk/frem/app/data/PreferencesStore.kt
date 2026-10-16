package dk.frem.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("frem_preferences")

class PreferencesStore(private val context: Context) {
    val flow =
        context.preferences.data.map { p ->
            fun reminder(kind: ReminderKind, defaultTime: Int) =
                ReminderSetting(
                    p[booleanPreferencesKey("reminder_${kind.name}_enabled")] ?: false,
                    p[intPreferencesKey("reminder_${kind.name}_time")] ?: defaultTime,
                    p[stringPreferencesKey("reminder_${kind.name}_sent")],
                )
            Preferences(
                stepGoal = p[intPreferencesKey("step_goal")] ?: 8000,
                waterGoal = p[intPreferencesKey("water_goal")] ?: 2000,
                theme = p[stringPreferencesKey("theme")] ?: "system",
                dynamicColor = p[booleanPreferencesKey("dynamic")] ?: true,
                imperial = p[booleanPreferencesKey("imperial")] ?: false,
                backgroundSync = p[booleanPreferencesKey("background")] ?: false,
                connected = p[booleanPreferencesKey("connected")] ?: false,
                weightReminder = reminder(ReminderKind.WEIGHT, 8 * 60 + 30),
                waterReminder = reminder(ReminderKind.WATER, 14 * 60),
                stepReminder = reminder(ReminderKind.STEPS, 19 * 60),
            )
        }

    suspend fun number(key: String, value: Int) {
        context.preferences.edit { it[intPreferencesKey(key)] = value }
    }

    suspend fun text(key: String, value: String) {
        context.preferences.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun flag(key: String, value: Boolean) {
        context.preferences.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun reminder(kind: ReminderKind, enabled: Boolean? = null, minute: Int? = null) {
        require(minute == null || minute in 0..1439)
        context.preferences.edit {
            enabled?.let { value ->
                it[booleanPreferencesKey("reminder_${kind.name}_enabled")] = value
            }
            minute?.let { value -> it[intPreferencesKey("reminder_${kind.name}_time")] = value }
        }
    }

    suspend fun reminderSent(kind: ReminderKind, date: String) {
        text("reminder_${kind.name}_sent", date)
    }
}
